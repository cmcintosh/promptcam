/**
 * app.js — PromptCam Desktop Renderer Application
 *
 * Handles all renderer-side logic:
 *   • Device discovery display and manual IP entry
 *   • Connect / disconnect
 *   • Video frame display (H.264 via WebCodecs VideoDecoder → canvas)
 *   • Audio decoding (AAC via WebCodecs AudioDecoder → Web Audio API)
 *   • Script editing and sending to phone
 *   • Voice sync start / stop
 *   • Scroll position display and manual control
 *   • Virtual camera start / stop
 *   • Streaming stats (FPS, frame count, bytes received)
 *   • Status bar updates and toast notifications
 *
 * Uses the `window.promptcam` API exposed by preload.js.
 */

(function () {
  'use strict';

  // ═══════════════════════════════════════════════════════════════════════
  // DOM HELPER
  // ═══════════════════════════════════════════════════════════════════════

  const $ = (id) => document.getElementById(id);

  // ═══════════════════════════════════════════════════════════════════════
  // STATE
  // ═══════════════════════════════════════════════════════════════════════

  let isConnected = false;
  let isVoiceSyncRunning = false;

  // Streaming stats
  let frameCount = 0;
  let audioFrameCount = 0;
  let bytesReceived = 0;
  let fpsCounter = 0;
  let lastFpsTime = performance.now();

  // Debounce timer for script sending
  let debounceTimer = null;
  const DEBOUNCE_MS = 800;

  // WebCodecs decoders
  let videoDecoder = null;
  let videoDecoderConfigured = false;
  let audioDecoder = null;
  let audioDecoderConfigured = false;

  // Web Audio API
  let audioContext = null;
  let audioStartTime = 0;

  // Unsubscribe functions for event listeners
  const unsubscribers = [];

  // ═══════════════════════════════════════════════════════════════════════
  // TOAST NOTIFICATIONS
  // ═══════════════════════════════════════════════════════════════════════

  const toastContainer = $('toast-container');

  function showToast(message, type = 'info', duration = 3500) {
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    toastContainer.appendChild(toast);

    // Trigger slide-in animation
    requestAnimationFrame(() => toast.classList.add('show'));

    setTimeout(() => {
      toast.classList.remove('show');
      setTimeout(() => toast.remove(), 300);
    }, duration);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // UTILITY FUNCTIONS
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Format bytes into human-readable string.
   */
  function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  /**
   * Estimate read time from word count (150 wpm average).
   */
  function estimateReadTime(words) {
    const minutes = words / 150;
    if (minutes < 1) return `~${Math.ceil(minutes * 60)} sec read`;
    return `~${Math.ceil(minutes)} min read`;
  }

  /**
   * Check if an H.264 NAL unit is a keyframe (IDR slice).
   * NAL type is in the first byte's lower 5 bits.
   * Type 5 = IDR slice (keyframe).
   */
  function isH264Keyframe(nalData) {
    if (!nalData || nalData.length < 1) return false;
    const nalType = nalData[0] & 0x1f;
    return nalType === 5; // IDR slice
  }

  /**
   * Check if an H.264 NAL unit is SPS (type 7) or PPS (type 8).
   */
  function isH264Config(nalData) {
    if (!nalData || nalData.length < 1) return false;
    const nalType = nalData[0] & 0x1f;
    return nalType === 7 || nalType === 8;
  }

  // ═══════════════════════════════════════════════════════════════════════
  // STATUS MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════

  const statusIndicator = $('status-indicator');
  const statusText = statusIndicator ? statusIndicator.querySelector('.status-text') : null;

  function setStatus(status, message) {
    if (statusIndicator) {
      statusIndicator.className = `status ${status}`;
    }
    if (statusText) {
      statusText.textContent = message || status;
    }

    isConnected = status === 'connected';

    // Enable/disable buttons based on connection state
    if ($('connect-btn')) $('connect-btn').disabled = isConnected;
    if ($('disconnect-btn')) $('disconnect-btn').disabled = !isConnected;
    if ($('send-script-btn')) $('send-script-btn').disabled = !isConnected;
    if ($('voice-start-btn')) $('voice-start-btn').disabled = !isConnected || isVoiceSyncRunning;

    // Show/hide preview placeholder
    const placeholder = $('preview-placeholder');
    if (placeholder) {
      placeholder.style.display = isConnected ? 'none' : 'flex';
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // STREAMING STATS
  // ═══════════════════════════════════════════════════════════════════════

  function updateStats() {
    const fpsEl = $('stat-fps');
    const framesEl = $('stat-frames');
    const bytesEl = $('stat-bytes');
    const audioEl = $('stat-audio');

    // Calculate FPS every second
    const now = performance.now();
    const elapsed = now - lastFpsTime;
    if (elapsed >= 1000) {
      const fps = Math.round((fpsCounter * 1000) / elapsed);
      if (fpsEl) fpsEl.textContent = `${fps} fps`;
      fpsCounter = 0;
      lastFpsTime = now;
    }

    if (framesEl) framesEl.textContent = frameCount.toLocaleString();
    if (bytesEl) bytesEl.textContent = formatBytes(bytesReceived);
    if (audioEl) audioEl.textContent = audioFrameCount.toLocaleString();
  }

  // Update stats display every 500ms
  setInterval(updateStats, 500);

  // ═══════════════════════════════════════════════════════════════════════
  // VIDEO DECODING (WebCodecs)
  // ═══════════════════════════════════════════════════════════════════════

  const videoCanvas = $('video-canvas');
  const canvasCtx = videoCanvas ? videoCanvas.getContext('2d') : null;

  // Fill canvas with black initially
  if (canvasCtx) {
    canvasCtx.fillStyle = '#000';
    canvasCtx.fillRect(0, 0, videoCanvas.width, videoCanvas.height);
  }

  function ensureVideoDecoder() {
    if (videoDecoderConfigured) return true;

    if (typeof VideoDecoder === 'undefined') {
      // WebCodecs not available — show fallback message
      const placeholder = $('preview-placeholder');
      if (placeholder) {
        placeholder.style.display = 'flex';
        placeholder.innerHTML = '<p>WebCodecs API not available.<br>Video preview requires Chrome 94+.</p>';
      }
      return false;
    }

    try {
      videoDecoder = new VideoDecoder({
        output: (frame) => {
          if (canvasCtx && videoCanvas) {
            canvasCtx.drawImage(frame, 0, 0, videoCanvas.width, videoCanvas.height);
          }
          frame.close();
        },
        error: (err) => {
          console.error('[VideoDecoder] Error:', err);
        },
      });

      videoDecoder.configure({
        codec: 'avc1.42E01E', // H.264 Baseline Level 3.1
        codedWidth: 1280,
        codedHeight: 720,
        optimizeForLatency: true,
      });

      videoDecoderConfigured = true;
      return true;
    } catch (err) {
      console.error('[VideoDecoder] Setup failed:', err);
      return false;
    }
  }

  function handleVideoFrame(frameData) {
    frameCount++;
    fpsCounter++;
    bytesReceived += frameData.byteLength || frameData.length;

    if (!ensureVideoDecoder()) return;

    // Determine if this is a keyframe or delta frame
    // H.264 NAL units: type 5 = IDR (keyframe), type 1 = non-IDR (delta)
    const data = new Uint8Array(frameData);
    const keyframe = isH264Keyframe(data);

    try {
      // Check decoder decode queue depth for backpressure
      if (videoDecoder.decodeQueueSize > 30) {
        // Drop delta frames under pressure, but never keyframes
        if (!keyframe) return;
      }

      const chunk = new EncodedVideoChunk({
        type: keyframe ? 'key' : 'delta',
        timestamp: performance.now() * 1000, // microseconds
        data: data,
      });
      videoDecoder.decode(chunk);
    } catch (err) {
      // If decode fails, try reconfiguring on next keyframe
      if (keyframe) {
        console.warn('[VideoDecoder] Decode failed, reconfiguring:', err.message);
        videoDecoderConfigured = false;
        ensureVideoDecoder();
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // AUDIO DECODING (WebCodecs + Web Audio API)
  // ═══════════════════════════════════════════════════════════════════════

  function ensureAudioDecoder() {
    if (audioDecoderConfigured) return true;

    if (typeof AudioDecoder === 'undefined') {
      // WebCodecs AudioDecoder not available — audio playback skipped
      return false;
    }

    try {
      // Initialize Web Audio API context
      if (!audioContext) {
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        audioStartTime = audioContext.currentTime;
      }

      audioDecoder = new AudioDecoder({
        output: (audioData) => {
          playAudioData(audioData);
        },
        error: (err) => {
          console.error('[AudioDecoder] Error:', err);
        },
      });

      audioDecoder.configure({
        codec: 'mp4a.40.2', // AAC LC
        sampleRate: 16000,
        numberOfChannels: 1,
      });

      audioDecoderConfigured = true;
      return true;
    } catch (err) {
      console.error('[AudioDecoder] Setup failed:', err);
      return false;
    }
  }

  /**
   * Play decoded audio data via Web Audio API.
   */
  function playAudioData(audioData) {
    if (!audioContext) return;

    try {
      // Convert AudioData to AudioBuffer
      const buffer = audioContext.createBuffer(
        audioData.numberOfChannels,
        audioData.numberOfFrames,
        audioData.sampleRate,
      );

      // Copy channel data
      for (let ch = 0; ch < audioData.numberOfChannels; ch++) {
        const channelData = new Float32Array(audioData.numberOfFrames);
        audioData.copyTo(channelData, { planeIndex: ch });
        buffer.copyToChannel(channelData, ch);
      }

      const source = audioContext.createBufferSource();
      source.buffer = buffer;
      source.connect(audioContext.destination);

      // Schedule playback
      const now = audioContext.currentTime;
      if (now > audioStartTime) {
        source.start(now);
        audioStartTime = now + buffer.duration;
      } else {
        source.start(audioStartTime);
        audioStartTime += buffer.duration;
      }

      audioData.close();
    } catch (err) {
      console.error('[Audio] Playback error:', err);
      try { audioData.close(); } catch (_) { /* already closed */ }
    }
  }

  function handleAudioFrame(audioData) {
    audioFrameCount++;
    bytesReceived += audioData.byteLength || audioData.length;

    if (!ensureAudioDecoder()) return;

    try {
      const chunk = new EncodedAudioChunk({
        type: 'key',
        timestamp: performance.now() * 1000,
        data: new Uint8Array(audioData),
      });
      audioDecoder.decode(chunk);
    } catch (err) {
      console.error('[AudioDecoder] Decode failed:', err);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // SCRIPT MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════

  const scriptTextarea = $('script-textarea');
  const wordCountEl = $('word-count');
  const readTimeEl = $('read-time');
  const fontSizeSlider = $('font-size-slider');
  const fontSizeValue = $('font-size-value');
  const speedSlider = $('speed-slider');
  const speedValue = $('speed-value');
  const sendScriptBtn = $('send-script-btn');
  const manualScrollSlider = $('manual-scroll-slider');
  const manualScrollValue = $('manual-scroll-value');

  function updateScriptStats() {
    if (!scriptTextarea) return;
    const text = scriptTextarea.value.trim();
    const words = text ? text.split(/\s+/).length : 0;
    if (wordCountEl) wordCountEl.textContent = `${words} word${words !== 1 ? 's' : ''}`;
    if (readTimeEl) readTimeEl.textContent = estimateReadTime(words);
  }

  function sendScript() {
    if (!isConnected || !scriptTextarea) return;

    const text = scriptTextarea.value;
    const fontSize = parseInt(fontSizeSlider ? fontSizeSlider.value : 24, 10);
    const speed = parseFloat(speedSlider ? speedSlider.value : 1.0);

    window.promptcam.sendScript(text, fontSize, speed).then((result) => {
      if (!result.success) {
        showToast(`Failed to send script: ${result.error}`, 'error');
      } else {
        showToast('Script sent to phone', 'success', 2000);
      }
    });
  }

  function sendManualScroll() {
    if (!manualScrollSlider) return;

    const raw = parseInt(manualScrollSlider.value, 10);
    const percent = raw / 1000; // slider is 0–1000 for fine control

    if (manualScrollValue) manualScrollValue.textContent = `${Math.round(percent * 100)}%`;

    if (isConnected) {
      window.promptcam.sendScrollPosition(percent).then((result) => {
        if (!result.success) {
          console.warn('Failed to send scroll position:', result.error);
        }
      });
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // CONNECTION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════

  const deviceSelect = $('device-select');
  const manualIp = $('manual-ip');
  const connectBtn = $('connect-btn');
  const disconnectBtn = $('disconnect-btn');

  async function handleConnect() {
    let host = manualIp ? manualIp.value.trim() : '';

    // If no manual IP, use selected device
    if (!host && deviceSelect && deviceSelect.value) {
      try {
        const device = JSON.parse(deviceSelect.value);
        host = device.host;
      } catch (e) {
        // ignore parse errors
      }
    }

    if (!host) {
      showToast('Please select a device or enter an IP address', 'error');
      return;
    }

    // Determine ports from selected device if available
    let port = 8554;
    let tpPort = 8555;
    if (deviceSelect && deviceSelect.value) {
      try {
        const device = JSON.parse(deviceSelect.value);
        if (device.port) port = device.port;
        if (device.tpPort) tpPort = device.tpPort;
      } catch (e) { /* use defaults */ }
    }

    setStatus('connecting', `Connecting to ${host}…`);

    const result = await window.promptcam.connect(host, port, tpPort);
    if (!result.success) {
      setStatus('error', result.error || 'Connection failed');
      showToast(`Connection failed: ${result.error}`, 'error');
    }
  }

  async function handleDisconnect() {
    await window.promptcam.disconnect();

    // Reset state
    frameCount = 0;
    audioFrameCount = 0;
    bytesReceived = 0;
    fpsCounter = 0;

    // Reset decoders
    if (videoDecoder) {
      try { videoDecoder.reset(); } catch (_) { /* already closed */ }
      videoDecoderConfigured = false;
    }
    if (audioDecoder) {
      try { audioDecoder.reset(); } catch (_) { /* already closed */ }
      audioDecoderConfigured = false;
    }

    setStatus('disconnected', 'Disconnected');
    showToast('Disconnected', 'info', 2000);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // VOICE SYNC MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════

  const voiceStartBtn = $('voice-start-btn');
  const voiceStopBtn = $('voice-stop-btn');
  const voiceStatusText = $('voice-status-text');
  const partialTextEl = $('partial-text');
  const scrollBar = $('scroll-bar');
  const scrollPercentEl = $('scroll-percent');
  const confidenceBar = $('confidence-bar');
  const confidencePercentEl = $('confidence-percent');

  async function handleStartVoiceSync() {
    if (!scriptTextarea || !scriptTextarea.value.trim()) {
      showToast('Please enter a script before starting voice sync', 'error');
      return;
    }

    const result = await window.promptcam.startVoiceSync(scriptTextarea.value);
    if (result.success) {
      isVoiceSyncRunning = true;
      if (voiceStartBtn) voiceStartBtn.disabled = true;
      if (voiceStopBtn) voiceStopBtn.disabled = false;
      if (voiceStatusText) {
        voiceStatusText.textContent = 'Listening…';
        voiceStatusText.classList.add('listening');
      }
      showToast('Voice sync started', 'success', 2000);
    } else {
      showToast(`Failed to start voice sync: ${result.error}`, 'error');
    }
  }

  async function handleStopVoiceSync() {
    await window.promptcam.stopVoiceSync();
    isVoiceSyncRunning = false;
    if (voiceStartBtn) voiceStartBtn.disabled = !isConnected;
    if (voiceStopBtn) voiceStopBtn.disabled = true;
    if (voiceStatusText) {
      voiceStatusText.textContent = 'Idle';
      voiceStatusText.classList.remove('listening');
    }
    showToast('Voice sync stopped', 'info', 2000);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // VIRTUAL CAMERA MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════

  const vcToggle = $('virtual-camera-toggle');
  const vcStatusEl = $('vc-status');

  async function handleVirtualCameraToggle() {
    if (!vcToggle) return;

    if (vcToggle.checked) {
      const result = await window.promptcam.startVirtualCamera(1280, 720);
      if (result.success) {
        if (vcStatusEl) {
          vcStatusEl.textContent = 'Active';
          vcStatusEl.className = 'vc-status active';
        }
        showToast('Virtual camera started', 'success', 2000);
      } else {
        vcToggle.checked = false;
        showToast(`Virtual camera failed: ${result.error}`, 'error');
      }
    } else {
      await window.promptcam.stopVirtualCamera();
      if (vcStatusEl) {
        vcStatusEl.textContent = 'Inactive';
        vcStatusEl.className = 'vc-status inactive';
      }
      showToast('Virtual camera stopped', 'info', 2000);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // SETTINGS MODAL
  // ═══════════════════════════════════════════════════════════════════════

  const settingsBtn = $('settings-btn');
  const settingsModal = $('settings-modal');
  const settingsClose = $('settings-close');
  const settingsSave = $('settings-save');

  function openSettings() {
    if (settingsModal) settingsModal.classList.remove('hidden');
  }

  function closeSettings() {
    if (settingsModal) settingsModal.classList.add('hidden');
  }

  function handleSaveSettings() {
    // Read settings values
    const scrollMult = parseFloat(($('scroll-speed-mult') || {}).value || 1.0);

    // Handle virtual camera toggle
    if (vcToggle && vcToggle.checked) {
      window.promptcam.startVirtualCamera(1280, 720).then((result) => {
        if (!result.success) {
          showToast(`Virtual camera: ${result.error}`, 'error');
          if (vcToggle) vcToggle.checked = false;
        }
      });
    } else {
      window.promptcam.stopVirtualCamera();
    }

    closeSettings();
    showToast('Settings saved', 'success', 2000);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // EVENT LISTENER REGISTRATION
  // ═══════════════════════════════════════════════════════════════════════

  function registerDomEvents() {
    // Connection
    if (connectBtn) connectBtn.addEventListener('click', handleConnect);
    if (disconnectBtn) disconnectBtn.addEventListener('click', handleDisconnect);

    // Script editor
    if (scriptTextarea) {
      scriptTextarea.addEventListener('input', () => {
        updateScriptStats();
        if (isConnected && !isVoiceSyncRunning) {
          clearTimeout(debounceTimer);
          debounceTimer = setTimeout(sendScript, DEBOUNCE_MS);
        }
      });
    }

    if (sendScriptBtn) sendScriptBtn.addEventListener('click', sendScript);

    // Font size slider
    if (fontSizeSlider) {
      fontSizeSlider.addEventListener('input', () => {
        if (fontSizeValue) fontSizeValue.textContent = `${fontSizeSlider.value}pt`;
        if (isConnected) {
          clearTimeout(debounceTimer);
          debounceTimer = setTimeout(sendScript, DEBOUNCE_MS);
        }
      });
    }

    // Speed slider
    if (speedSlider) {
      speedSlider.addEventListener('input', () => {
        if (speedValue) speedValue.textContent = `${parseFloat(speedSlider.value).toFixed(1)}×`;
        if (isConnected) {
          clearTimeout(debounceTimer);
          debounceTimer = setTimeout(sendScript, DEBOUNCE_MS);
        }
      });
    }

    // Manual scroll slider
    if (manualScrollSlider) {
      manualScrollSlider.addEventListener('input', sendManualScroll);
    }

    // Voice sync
    if (voiceStartBtn) voiceStartBtn.addEventListener('click', handleStartVoiceSync);
    if (voiceStopBtn) voiceStopBtn.addEventListener('click', handleStopVoiceSync);

    // Settings
    if (settingsBtn) settingsBtn.addEventListener('click', openSettings);
    if (settingsClose) settingsClose.addEventListener('click', closeSettings);
    if (settingsModal) {
      settingsModal.addEventListener('click', (e) => {
        if (e.target === settingsModal) closeSettings();
      });
    }
    if (settingsSave) settingsSave.addEventListener('click', handleSaveSettings);

    // Virtual camera toggle
    if (vcToggle) vcToggle.addEventListener('change', handleVirtualCameraToggle);

    // Scroll speed multiplier display
    const scrollMultEl = $('scroll-speed-mult');
    if (scrollMultEl) {
      scrollMultEl.addEventListener('input', () => {
        const valEl = $('scroll-speed-mult-value');
        if (valEl) valEl.textContent = `${parseFloat(scrollMultEl.value).toFixed(1)}×`;
      });
    }

    // Device select — auto-fill IP when a device is chosen
    if (deviceSelect) {
      deviceSelect.addEventListener('change', () => {
        if (deviceSelect.value && manualIp) {
          try {
            const device = JSON.parse(deviceSelect.value);
            manualIp.value = device.host;
          } catch (e) { /* ignore */ }
        }
      });
    }
  }

  function registerIpcEvents() {
    // Connection status
    unsubscribers.push(
      window.promptcam.onConnectionStatus((data) => {
        setStatus(data.status, data.message);
        if (data.status === 'error') {
          showToast(data.message, 'error');
        }
      }),
    );

    // Discovered devices
    unsubscribers.push(
      window.promptcam.onDiscoveredDevices((devices) => {
        if (!deviceSelect) return;

        // Clear existing options except the placeholder
        deviceSelect.innerHTML = '<option value="">— Select Device —</option>';

        devices.forEach((device) => {
          const option = document.createElement('option');
          option.value = JSON.stringify(device);
          option.textContent = `${device.name} (${device.host})`;
          deviceSelect.appendChild(option);
        });
      }),
    );

    // Video frames
    unsubscribers.push(
      window.promptcam.onStreamVideoFrame((frameData) => {
        handleVideoFrame(frameData);
      }),
    );

    // Audio frames
    unsubscribers.push(
      window.promptcam.onStreamAudioFrame((audioData) => {
        handleAudioFrame(audioData);
      }),
    );

    // Voice sync: partial results
    unsubscribers.push(
      window.promptcam.onVoicePartial((text) => {
        if (partialTextEl) {
          partialTextEl.textContent = text || '—';
          // Subtle pulse animation
          partialTextEl.classList.remove('pulse');
          void partialTextEl.offsetWidth; // force reflow
          partialTextEl.classList.add('pulse');
        }
      }),
    );

    // Voice sync: scroll position
    unsubscribers.push(
      window.promptcam.onVoiceScroll((percent) => {
        const pct = Math.round(percent * 100);
        if (scrollBar) scrollBar.style.width = `${pct}%`;
        if (scrollPercentEl) scrollPercentEl.textContent = `${pct}%`;

        // Sync manual scroll slider to voice-driven position
        if (manualScrollSlider) {
          manualScrollSlider.value = Math.round(percent * 1000);
          if (manualScrollValue) manualScrollValue.textContent = `${pct}%`;
        }
      }),
    );

    // Voice sync: confidence
    unsubscribers.push(
      window.promptcam.onVoiceConfidence((confidence) => {
        const pct = Math.round(confidence * 100);
        if (confidenceBar) {
          confidenceBar.style.width = `${pct}%`;
          // Color-code: green >= 70%, yellow >= 40%, red < 40%
          if (pct >= 70) {
            confidenceBar.className = 'confidence-bar high';
          } else if (pct >= 40) {
            confidenceBar.className = 'confidence-bar medium';
          } else {
            confidenceBar.className = 'confidence-bar low';
          }
        }
        if (confidencePercentEl) confidencePercentEl.textContent = `${pct}%`;
      }),
    );
  }

  // ═══════════════════════════════════════════════════════════════════════
  // INITIALIZATION
  // ═══════════════════════════════════════════════════════════════════════

  function init() {
    registerDomEvents();
    registerIpcEvents();
    updateScriptStats();
    setStatus('disconnected', 'Disconnected');

    // Disable voice stop button initially
    if (voiceStopBtn) voiceStopBtn.disabled = true;

    // Resume audio context on first user interaction (browser autoplay policy)
    document.addEventListener('click', () => {
      if (audioContext && audioContext.state === 'suspended') {
        audioContext.resume();
      }
    }, { once: true });

    console.log('[PromptCam] Renderer initialized');
  }

  // Cleanup on page unload
  window.addEventListener('beforeunload', () => {
    unsubscribers.forEach((unsub) => {
      try { unsub(); } catch (_) { /* already removed */ }
    });
    if (videoDecoder) {
      try { videoDecoder.close(); } catch (_) { /* already closed */ }
    }
    if (audioDecoder) {
      try { audioDecoder.close(); } catch (_) { /* already closed */ }
    }
    if (audioContext) {
      try { audioContext.close(); } catch (_) { /* already closed */ }
    }
  });

  // Start the app
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
