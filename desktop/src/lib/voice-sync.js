/**
 * voice-sync.js — Voice-driven scroll synchronization.
 *
 * Manages a Vosk worker thread for speech recognition, matches recognized
 * words against the script, and computes the current scroll position as a
 * percentage (0.0 = beginning, 1.0 = end).
 *
 * Emits:
 *   • 'partialResult'   (string)  — latest partial recognition text
 *   • 'scrollPosition'  (float)   — scroll percentage 0.0–1.0
 *   • 'matchConfidence' (float)   — how well the recognition matches the script (0.0–1.0)
 *
 * Usage:
 *   const vs = new VoiceSync(scriptText);
 *   vs.on('scrollPosition', (pct) => telepromptServer.sendScrollPosition(pct));
 *   vs.start();
 *   // Feed audio:
 *   vs.feedAudio(aacBuffer);
 *   // Stop:
 *   vs.stop();
 */

const { EventEmitter } = require('events');
const { Worker } = require('worker_threads');
const path = require('path');

// Worker script path
const WORKER_PATH = path.join(__dirname, '..', 'worker', 'vosk-worker.js');

// Default Vosk model path — can be overridden
const DEFAULT_MODEL_PATH = path.join(
  process.cwd(),
  'models',
  'vosk-model-small-en-us-0.15',
);

// Throttle: send scroll updates at most every 100ms (~10 fps)
const SCROLL_THROTTLE_MS = 100;

// Number of words to use as a sliding window for fuzzy matching
const MATCH_WINDOW_SIZE = 8;

// Minimum number of words recognized before we start computing scroll position
const MIN_WORDS_FOR_MATCH = 3;

class VoiceSync extends EventEmitter {
  /**
   * @param {string} scriptText - The full script to match against
   * @param {object} opts
   * @param {string} opts.modelPath - Path to Vosk model directory
   * @param {number} opts.sampleRate - Audio sample rate for Vosk (default 16000)
   * @param {number} opts.scrollSpeedMultiplier - Multiplier for scroll speed (default 1.0)
   */
  constructor(scriptText, opts = {}) {
    super();
    this.scriptText = scriptText || '';
    this.modelPath = opts.modelPath || DEFAULT_MODEL_PATH;
    this.sampleRate = opts.sampleRate || 16000;
    this.scrollSpeedMultiplier = opts.scrollSpeedMultiplier || 1.0;

    this.worker = null;
    this.isRunning = false;
    this._lastScrollSent = 0;
    this._lastScrollValue = 0;

    // Pre-process the script into an array of lowercase words for matching
    this._scriptWords = this._tokenize(scriptText);
    this._scriptWordCount = this._scriptWords.length;

    // Track the current match position in the script
    this._currentScriptIndex = 0;

    // Accumulated recognized words for the current matching window
    this._recentWords = [];

    // Manual override flag — when true, voice-driven scroll is paused
    this._manualOverride = false;
    this._manualOverrideTimer = null;
  }

  // ── Public API ──────────────────────────────────────────────────────

  /**
   * Start the Vosk worker and begin listening for audio.
   */
  start() {
    if (this.isRunning) return;
    this.isRunning = true;

    // Spawn the Vosk worker thread
    this.worker = new Worker(WORKER_PATH, {
      workerData: {
        modelPath: this.modelPath,
        sampleRate: this.sampleRate,
      },
    });

    this.worker.on('message', (msg) => {
      switch (msg.type) {
        case 'ready':
          console.log('[VoiceSync] Vosk worker ready');
          this.emit('matchConfidence', 0);
          break;
        case 'partial':
          this._handlePartialResult(msg.text);
          break;
        case 'result':
          this._handleFinalResult(msg.text);
          break;
        case 'error':
          console.error('[VoiceSync] Worker error:', msg.message);
          this.emit('matchConfidence', 0);
          break;
        case 'stopped':
          console.log('[VoiceSync] Worker stopped');
          break;
      }
    });

    this.worker.on('error', (err) => {
      console.error('[VoiceSync] Worker thread error:', err.message);
      this.isRunning = false;
    });

    this.worker.on('exit', (code) => {
      console.log(`[VoiceSync] Worker exited with code ${code}`);
      this.isRunning = false;
    });
  }

  /**
   * Stop the Vosk worker and clean up.
   */
  stop() {
    if (this.worker) {
      this.worker.postMessage({ type: 'stop' });
      this.worker.terminate().catch(() => {});
      this.worker = null;
    }
    this.isRunning = false;
    this._recentWords = [];
    this._currentScriptIndex = 0;

    if (this._manualOverrideTimer) {
      clearTimeout(this._manualOverrideTimer);
      this._manualOverrideTimer = null;
    }
  }

  /**
   * Feed audio data to the Vosk worker for recognition.
   * Expected format: AAC or PCM data from the stream client.
   * If AAC, it must be decoded to PCM first — the caller (main.js)
   * forwards raw frames; this method expects PCM after decoding.
   *
   * For now, this accepts raw PCM 16-bit mono 16kHz buffers.
   * If AAC data arrives, it should be decoded in the renderer via
   * WebCodecs AudioDecoder and the PCM sent back through IPC.
   *
   * @param {Buffer|Uint8Array} audioData - PCM 16-bit mono 16kHz audio
   */
  feedAudio(audioData) {
    if (!this.isRunning || !this.worker) return;

    // Forward PCM data to the worker
    // Note: If the incoming data is AAC (from the stream), it must be
    // decoded to PCM first. In the current architecture, audio frames
    // are sent to the renderer for WebCodecs decoding, and the resulting
    // PCM is forwarded back. For a simpler pipeline, we accept PCM here.
    this.worker.postMessage(
      { type: 'audio', data: Buffer.from(audioData) },
      [Buffer.from(audioData).buffer], // transfer for performance
    );
  }

  /**
   * Temporarily pause voice-driven scroll (e.g. when user manually scrolls).
   * Automatically resumes after 5 seconds of no manual input.
   */
  pauseForManualOverride() {
    this._manualOverride = true;
    if (this._manualOverrideTimer) {
      clearTimeout(this._manualOverrideTimer);
    }
    this._manualOverrideTimer = setTimeout(() => {
      this._manualOverride = false;
      this._manualOverrideTimer = null;
      // Reset the matching window after manual override
      this._recentWords = [];
    }, 5000);
  }

  /**
   * Update the script text (e.g. after user edits).
   */
  updateScript(newText) {
    this.scriptText = newText;
    this._scriptWords = this._tokenize(newText);
    this._scriptWordCount = this._scriptWords.length;
    this._currentScriptIndex = 0;
    this._recentWords = [];

    // Reset the Vosk recognizer to clear stale context
    if (this.worker) {
      this.worker.postMessage({ type: 'reset' });
    }
  }

  // ── Internal: text processing ────────────────────────────────────────

  /**
   * Tokenize text into lowercase word tokens, stripping punctuation.
   * @param {string} text
   * @returns {string[]}
   */
  _tokenize(text) {
    return text
      .toLowerCase()
      .replace(/[^\w\s']/g, ' ') // strip punctuation except apostrophes
      .split(/\s+/)
      .filter((w) => w.length > 0);
  }

  // ── Internal: recognition result handling ────────────────────────────

  /**
   * Handle a partial recognition result. This is called frequently
   * as Vosk updates its best-guess text in real time.
   */
  _handlePartialResult(text) {
    if (!text || text.trim().length === 0) return;

    this.emit('partialResult', text);

    if (this._manualOverride) return;

    const words = this._tokenize(text);
    if (words.length < MIN_WORDS_FOR_MATCH) return;

    // Use the last N words as a sliding window for matching
    const window = words.slice(-MATCH_WINDOW_SIZE);
    const { index, confidence } = this._fuzzyMatchScript(window);

    if (index >= 0 && confidence > 0.3) {
      // Update the current position — only move forward, never backward
      if (index > this._currentScriptIndex) {
        this._currentScriptIndex = index;
      }

      this._computeAndSendScroll();
      this.emit('matchConfidence', confidence);
    }
  }

  /**
   * Handle a final recognition result. Final results are more reliable
   * than partials — use them to firmly anchor the position.
   */
  _handleFinalResult(text) {
    if (!text || text.trim().length === 0) return;

    if (this._manualOverride) return;

    const words = this._tokenize(text);
    if (words.length === 0) return;

    // Use a larger window for final results since they're more reliable
    const window = words.slice(-MATCH_WINDOW_SIZE * 2);
    const { index, confidence } = this._fuzzyMatchScript(window);

    if (index >= 0) {
      // For final results, we can also move backward slightly (within 5 words)
      // in case the user repeated something
      this._currentScriptIndex = Math.max(this._currentScriptIndex, index);
      this._computeAndSendScroll();
      this.emit('matchConfidence', confidence);
    }
  }

  // ── Internal: fuzzy matching ─────────────────────────────────────────

  /**
   * Attempt to match a window of recognized words against the script.
   * Uses a sliding-window approach with a simple word-overlap score.
   *
   * @param {string[]} window - Recognized words to match
   * @returns {{index: number, confidence: number}} — index of best match
   *   in scriptWords, or -1 if no match found. Confidence is 0.0–1.0.
   */
  _fuzzyMatchScript(window) {
    if (this._scriptWordCount === 0 || window.length === 0) {
      return { index: -1, confidence: 0 };
    }

    // Search starting from the current position, looking ahead up to
    // 50 words to handle skips/repeats, and behind up to 10 words.
    const searchStart = Math.max(0, this._currentScriptIndex - 10);
    const searchEnd = Math.min(
      this._scriptWordCount - window.length,
      this._currentScriptIndex + 50,
    );

    let bestIndex = -1;
    let bestScore = 0;

    for (let i = searchStart; i <= searchEnd; i++) {
      let score = 0;
      for (let j = 0; j < window.length; j++) {
        const scriptWord = this._scriptWords[i + j];
        if (!scriptWord) break;

        if (this._wordSimilarity(window[j], scriptWord) > 0.7) {
          score++;
        }
      }
      // Normalize by window length
      score = score / window.length;

      if (score > bestScore) {
        bestScore = score;
        bestIndex = i;
      }
    }

    return { index: bestIndex, confidence: bestScore };
  }

  /**
   * Compute a simple similarity score between two words using
   * Levenshtein distance. Returns 0.0–1.0.
   */
  _wordSimilarity(a, b) {
    if (a === b) return 1.0;
    if (a.length === 0 || b.length === 0) return 0;

    const distance = this._levenshtein(a, b);
    const maxLen = Math.max(a.length, b.length);
    return 1.0 - distance / maxLen;
  }

  /**
   * Levenshtein edit distance between two strings.
   */
  _levenshtein(a, b) {
    const m = a.length;
    const n = b.length;
    const dp = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));

    for (let i = 0; i <= m; i++) dp[i][0] = i;
    for (let j = 0; j <= n; j++) dp[0][j] = j;

    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (a[i - 1] === b[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1];
        } else {
          dp[i][j] = 1 + Math.min(
            dp[i - 1][j],
            dp[i][j - 1],
            dp[i - 1][j - 1],
          );
        }
      }
    }

    return dp[m][n];
  }

  // ── Internal: scroll computation ─────────────────────────────────────

  _computeAndSendScroll() {
    if (this._scriptWordCount === 0) return;

    // Calculate scroll position as percentage through the script
    const rawPercent = this._currentScriptIndex / this._scriptWordCount;

    // Apply speed multiplier — values > 1.0 make the teleprompter
    // scroll faster than the actual speech position
    let percent = rawPercent * this.scrollSpeedMultiplier;
    percent = Math.max(0.0, Math.min(1.0, percent));

    // Throttle: only send if enough time has passed AND the change is significant
    const now = Date.now();
    const elapsed = now - this._lastScrollSent;
    const delta = Math.abs(percent - this._lastScrollValue);

    if (elapsed >= SCROLL_THROTTLE_MS || delta > 0.02) {
      this._lastScrollSent = now;
      this._lastScrollValue = percent;
      this.emit('scrollPosition', percent);
    }
  }
}

module.exports = VoiceSync;
