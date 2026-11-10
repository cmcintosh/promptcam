/**
 * vosk-worker.js — Speech recognition worker thread.
 *
 * Runs in a worker_threads Worker so Vosk's CPU-intensive recognition
 * does not block the Electron main process event loop.
 *
 * Messages from the parent thread:
 *   { type: 'init', modelPath: string, sampleRate: number }  — initialize
 *   { type: 'audio', data: Buffer }                          — feed PCM audio
 *   { type: 'reset' }                                        — reset recognizer
 *   { type: 'stop' }                                         — stop and clean up
 *
 * Messages to the parent thread:
 *   { type: 'ready' }                                        — model loaded
 *   { type: 'partial', text: string }                        — partial result
 *   { type: 'result', text: string }                         — final result
 *   { type: 'error', message: string }                       — error
 *   { type: 'stopped' }                                      — worker stopped
 */

const { parentPort, workerData } = require('worker_threads');
const path = require('path');
const fs = require('fs');

// Vosk is loaded lazily so that the worker can report a clean error
// if the native module is not installed.
let vosk = null;
let recognizer = null;
let model = null;
let initialized = false;

/**
 * Initialize the Vosk model and recognizer.
 * @param {string} modelPath - Path to the Vosk model directory
 * @param {number} sampleRate - Audio sample rate (default 16000)
 */
function init(modelPath, sampleRate = 16000) {
  try {
    // Try to load the vosk native module
    vosk = require('vosk');

    // Set log level (0 = silent, 1 = errors only)
    if (vosk.setLogLevel) {
      vosk.setLogLevel(1);
    }

    // Resolve model path relative to the app root if not absolute
    const resolvedPath = path.isAbsolute(modelPath)
      ? modelPath
      : path.resolve(process.cwd(), modelPath);

    if (!fs.existsSync(resolvedPath)) {
      parentPort.postMessage({
        type: 'error',
        message: `Vosk model not found at: ${resolvedPath}. Download a model from https://alphacephei.com/vosk/models and extract it.`,
      });
      return;
    }

    console.log(`[VoskWorker] Loading model from ${resolvedPath}…`);
    model = new vosk.Model(resolvedPath);
    recognizer = new vosk.Recognizer({ model, sampleRate });
    initialized = true;

    console.log('[VoskWorker] Model loaded, recognizer ready');
    parentPort.postMessage({ type: 'ready' });
  } catch (err) {
    parentPort.postMessage({
      type: 'error',
      message: `Failed to initialize Vosk: ${err.message}. Make sure the 'vosk' npm package is installed and a valid model path is provided.`,
    });
  }
}

/**
 * Feed PCM 16-bit mono audio data to the recognizer.
 * @param {Buffer} data - PCM audio buffer
 */
function feedAudio(data) {
  if (!initialized || !recognizer) return;

  try {
    // Vosk accepts raw PCM 16-bit mono data
    const result = recognizer.acceptWaveform(data);

    if (result) {
      // Final result — a complete phrase has been recognized
      const final = recognizer.finalResult();
      if (final && final.text) {
        parentPort.postMessage({ type: 'result', text: final.text });
      }
    }

    // Also send partial result (what's being recognized right now)
    const partial = recognizer.partialResult();
    if (partial && partial.partial) {
      parentPort.postMessage({ type: 'partial', text: partial.partial });
    }
  } catch (err) {
    console.error('[VoskWorker] Recognition error:', err.message);
  }
}

/**
 * Reset the recognizer state (clears partial results).
 */
function reset() {
  if (recognizer) {
    recognizer.reset();
  }
}

/**
 * Clean up and signal stop to the parent thread.
 */
function stop() {
  if (recognizer) {
    recognizer.free();
    recognizer = null;
  }
  if (model) {
    model.free();
    model = null;
  }
  initialized = false;
  parentPort.postMessage({ type: 'stopped' });
}

// ── Message handler ────────────────────────────────────────────────────

parentPort.on('message', (msg) => {
  switch (msg.type) {
    case 'init':
      init(msg.modelPath, msg.sampleRate || 16000);
      break;
    case 'audio':
      feedAudio(msg.data);
      break;
    case 'reset':
      reset();
      break;
    case 'stop':
      stop();
      break;
    default:
      console.warn(`[VoskWorker] Unknown message type: ${msg.type}`);
  }
});

// If workerData was passed at construction, auto-init
if (workerData && workerData.modelPath) {
  init(workerData.modelPath, workerData.sampleRate || 16000);
}
