/**
 * script-editor.js — Renderer-side script editor logic.
 *
 * Handles:
 *   • Script text input with debounced auto-send
 *   • Font size and scroll speed sliders
 *   • Manual scroll override slider
 *   • "Send to Phone" button
 *   • Word count and estimated read time
 *   • Connection status updates (enable/disable buttons)
 *   • Device dropdown population from mDNS discovery
 *
 * Communicates with the main process via window.promptcam (preload bridge).
 */

(function () {
  'use strict';

  // ── DOM references ────────────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);

  const deviceSelect = $('device-select');
  const manualIp = $('manual-ip');
  const connectBtn = $('connect-btn');
  const disconnectBtn = $('disconnect-btn');
  const statusIndicator = $('status-indicator');
  const statusText = statusIndicator.querySelector('.status-text');

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

  const voiceStartBtn = $('voice-start-btn');
  const voiceStopBtn = $('voice-stop-btn');

  const settingsBtn = $('settings-btn');
  const settingsModal = $('settings-modal');
  const settingsClose = $('settings-close');
  const settingsSave = $('settings-save');
  const virtualCameraToggle = $('virtual-camera-toggle');

  const videoCanvas = $('video-canvas');
  const previewPlaceholder = $('preview-placeholder');

  // ── State ─────────────────────────────────────────────────────────────
  let isConnected = false;
  let isVoiceSyncRunning = false;
  let debounceTimer = null;
  const DEBOUNCE_MS = 800;

  // ── Utility functions ─────────────────────────────────────────────────

  /**
   * Estimate read time from word count.
   * Average speaking rate ≈ 150 words per minute.
   */
  function estimateReadTime(words) {
    const minutes = words / 150;
    if (minutes < 1) {
      return `~${Math.ceil(minutes * 60)} sec read`;
    }
    return `~${Math.ceil(minutes)} min read`;
  }

  /**
   * Update word count and read time display.
   */
  function updateStats() {
    const text = scriptTextarea.value.trim();
    const words = text ? text.split(/\s+/).length : 0;
    wordCountEl.textContent = `${words} word${words !== 1 ? 's' : ''}`;
    readTimeEl.textContent = estimateReadTime(words);
  }

  /**
   * Set the connection status indicator.
   * @param {string} status — 'connected', 'disconnected', 'connecting', 'error'
   * @param {string} message — Display text
   */
  function setStatus(status, message) {
    statusIndicator.className = `status ${status}`;
    statusText.textContent = message || status;

    isConnected = status === 'connected';

    // Enable/disable buttons based on connection state
    connectBtn.disabled = isConnected;
    disconnectBtn.disabled = !isConnected;
    sendScriptBtn.disabled = !isConnected;
    voiceStartBtn.disabled = !isConnected || isVoiceSyncRunning;

    // Show/hide preview placeholder
    if (isConnected) {
      previewPlaceholder.style.display = 'none';
    } else {
      previewPlaceholder.style.display = 'flex';
    }
  }

  /**
   * Send the current script to the phone via IPC → teleprompt-server.
   */
  function sendScript() {
    const text = scriptTextarea.value;
    const fontSize = parseInt(fontSizeSlider.value, 10);
    const speed = parseFloat(speedSlider.value);

    window.promptcam.sendScript(text, fontSize, speed).then((result) => {
      if (!result.success) {
        console.warn('Failed to send script:', result.error);
      }
    });
  }

  /**
   * Send a manual scroll position to the phone.
   * When the user drags the manual scroll slider, voice sync is paused.
   */
  function sendManualScroll() {
    const raw = parseInt(manualScrollSlider.value, 10);
    const percent = raw / 1000; // slider is 0–1000 for fine control

    manualScrollValue.textContent = `${Math.round(percent * 100)}%`;

    // Pause voice sync temporarily — it will resume after 5s
    if (isVoiceSyncRunning) {
      // The main process handles the pause via the voice sync module
      // We just send the manual position which overrides
    }

    window.promptcam.sendScrollPosition(percent).then((result) => {
      if (!result.success) {
        console.warn('Failed to send scroll position:', result.error);
      }
    });
  }

  // ── Event listeners ───────────────────────────────────────────────────

  // Connect button
  connectBtn.addEventListener('click', async () => {
    let host = manualIp.value.trim();

    // If no manual IP, use selected device
    if (!host) {
      const selected = deviceSelect.value;
      if (selected) {
        const device = JSON.parse(selected);
        host = device.host;
      }
    }

    if (!host) {
      setStatus('error', 'Please select a device or enter an IP');
      return;
    }

    setStatus('connecting', `Connecting to ${host}…`);

    // Determine ports from selected device if available
    let port = 8554;
    let tpPort = 8555;
    if (deviceSelect.value) {
      try {
        const device = JSON.parse(deviceSelect.value);
        if (device.port) port = device.port;
        if (device.tpPort) tpPort = device.tpPort;
      } catch (e) {
        // ignore parse errors
      }
    }

    const result = await window.promptcam.connect(host, port, tpPort);
    if (!result.success) {
      setStatus('error', result.error || 'Connection failed');
    }
  });

  // Disconnect button
  disconnectBtn.addEventListener('click', async () => {
    await window.promptcam.disconnect();
    setStatus('disconnected', 'Disconnected');
  });

  // Script textarea — update stats immediately, debounce sends
  scriptTextarea.addEventListener('input', () => {
    updateStats();

    // Debounced auto-send while typing (only if connected)
    if (isConnected && !isVoiceSyncRunning) {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(sendScript, DEBOUNCE_MS);
    }
  });

  // Send script button
  sendScriptBtn.addEventListener('click', sendScript);

  // Font size slider
  fontSizeSlider.addEventListener('input', () => {
    fontSizeValue.textContent = `${fontSizeSlider.value}pt`;
    if (isConnected) {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(sendScript, DEBOUNCE_MS);
    }
  });

  // Speed slider
  speedSlider.addEventListener('input', () => {
    speedValue.textContent = `${parseFloat(speedSlider.value).toFixed(1)}×`;
    if (isConnected) {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(sendScript, DEBOUNCE_MS);
    }
  });

  // Manual scroll slider
  manualScrollSlider.addEventListener('input', sendManualScroll);

  // Voice sync start
  voiceStartBtn.addEventListener('click', async () => {
    const scriptText = scriptTextarea.value;
    if (!scriptText.trim()) {
      alert('Please enter a script before starting voice sync.');
      return;
    }

    const result = await window.promptcam.startVoiceSync(scriptText);
    if (result.success) {
      isVoiceSyncRunning = true;
      voiceStartBtn.disabled = true;
      voiceStopBtn.disabled = false;
      $('voice-status-text').textContent = 'Listening…';
    } else {
      alert(`Failed to start voice sync: ${result.error}`);
    }
  });

  // Voice sync stop
  voiceStopBtn.addEventListener('click', async () => {
    await window.promptcam.stopVoiceSync();
    isVoiceSyncRunning = false;
    voiceStartBtn.disabled = !isConnected;
    voiceStopBtn.disabled = true;
    $('voice-status-text').textContent = 'Idle';
  });

  // ── Settings modal ────────────────────────────────────────────────────

  settingsBtn.addEventListener('click', () => {
    settingsModal.classList.remove('hidden');
  });

  settingsClose.addEventListener('click', () => {
    settingsModal.classList.add('hidden');
  });

  settingsModal.addEventListener('click', (e) => {
    if (e.target === settingsModal) {
      settingsModal.classList.add('hidden');
    }
  });

  settingsSave.addEventListener('click', () => {
    // Settings are read by the voice-sync module at init time.
    // The scroll speed multiplier is applied in the main process.
    const scrollMult = parseFloat($('scroll-speed-mult').value);

    // If virtual camera toggle is on, start it
    if (virtualCameraToggle.checked) {
      window.promptcam.startVirtualCamera(1280, 720).then((result) => {
        if (!result.success) {
          console.warn('Virtual camera failed:', result.error);
        }
      });
    } else {
      window.promptcam.stopVirtualCamera();
    }

    settingsModal.classList.add('hidden');
  });

  // Scroll speed multiplier display
  $('scroll-speed-mult').addEventListener('input', () => {
    $('scroll-speed-mult-value').textContent =
      `${parseFloat($('scroll-speed-mult').value).toFixed(1)}×`;
  });

  // ── IPC event listeners (main → renderer) ─────────────────────────────

  // Connection status
  window.promptcam.onConnectionStatus((data) => {
    setStatus(data.status, data.message);
  });

  // Discovered devices
  window.promptcam.onDiscoveredDevices((devices) => {
    // Clear existing options except the placeholder
    deviceSelect.innerHTML = '<option value="">— Select Device —</option>';

    devices.forEach((device) => {
      const option = document.createElement('option');
      option.value = JSON.stringify(device);
      option.textContent = `${device.name} (${device.host})`;
      deviceSelect.appendChild(option);
    });
  });

  // Video frames — decode via WebCodecs and draw to canvas
  let videoDecoder = null;
  let decoderConfigured = false;

  window.promptcam.onStreamVideoFrame((frameData) => {
    // Use WebCodecs API to decode H.264 frames
    if (typeof VideoDecoder !== 'undefined' && !decoderConfigured) {
      try {
        videoDecoder = new VideoDecoder({
          output: (frame) => {
            // Draw the decoded frame to the canvas
            const ctx = videoCanvas.getContext('2d');
            ctx.drawImage(frame, 0, 0, videoCanvas.width, videoCanvas.height);
            frame.close();
          },
          error: (err) => {
            console.error('VideoDecoder error:', err);
          },
        });

        videoDecoder.configure({
          codec: 'avc1.42E01E', // H.264 Baseline
          codedWidth: 1280,
          codedHeight: 720,
        });
        decoderConfigured = true;
      } catch (err) {
        console.error('Failed to configure VideoDecoder:', err);
      }
    }

    // Decode the frame if decoder is ready
    if (videoDecoder && decoderConfigured) {
      try {
        const chunk = new EncodedVideoChunk({
          type: 'key', // Assume all keyframes for simplicity
          timestamp: Date.now() * 1000, // microseconds
          data: new Uint8Array(frameData),
        });
        videoDecoder.decode(chunk);
      } catch (err) {
        console.error('Failed to decode video frame:', err);
      }
    } else {
      // Fallback: if WebCodecs isn't available, we can't decode H.264 here.
      // In a production app, you'd use a WASM-based decoder (e.g. ffmpeg.wasm)
      // or the `h264-encoder` npm package.
    }
  });

  // Audio frames — for now, just forward to voice sync via main process
  // (The main process handles feeding audio to the Vosk worker)
  window.promptcam.onStreamAudioFrame((_audioData) => {
    // Audio decoding and playback would happen here using WebCodecs AudioDecoder.
    // For the current implementation, the main process forwards PCM data
    // to the Vosk worker after the renderer decodes it.
    // This is a hook for future AAC decoding implementation.
  });

  // Voice sync events
  window.promptcam.onVoicePartial((text) => {
    $('partial-text').textContent = text || '—';
  });

  window.promptcam.onVoiceScroll((percent) => {
    // Update the scroll bar UI
    const pct = Math.round(percent * 100);
    $('scroll-bar').style.width = `${pct}%`;
    $('scroll-percent').textContent = `${pct}%`;

    // Also update the manual scroll slider to reflect voice-driven position
    manualScrollSlider.value = Math.round(percent * 1000);
    manualScrollValue.textContent = `${pct}%`;
  });

  window.promptcam.onVoiceConfidence((confidence) => {
    const pct = Math.round(confidence * 100);
    $('confidence-bar').style.width = `${pct}%`;
    $('confidence-percent').textContent = `${pct}%`;
  });

  // ── Initialize ────────────────────────────────────────────────────────

  updateStats();
  setStatus('disconnected', 'Disconnected');

  // Request the current discovered devices list
  // (The main process will send them via the 'discovered-devices' event)
})();
