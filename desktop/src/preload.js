/**
 * preload.js — Secure bridge between the renderer (web page) and the main process.
 *
 * Uses contextBridge to expose a minimal, safe API surface. The renderer
 * cannot access Node.js APIs directly — only the functions explicitly
 * exposed here via ipcRenderer.invoke / ipcRenderer.on.
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('promptcam', {
  // ── Connection ──────────────────────────────────────────────────────
  /**
   * Connect to a PromptCam phone device.
   * @param {string} host - IP address or hostname of the phone
   * @param {number} [port=8554] - Video/audio stream port
   * @param {number} [tpPort=8555] - Teleprompter control port
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  connect: (host, port, tpPort) =>
    ipcRenderer.invoke('connect', { host, port, tpPort }),

  /**
   * Disconnect from the current device.
   * @returns {Promise<{success: boolean}>}
   */
  disconnect: () => ipcRenderer.invoke('disconnect'),

  // ── Script & teleprompter ────────────────────────────────────────────
  /**
   * Send script text to the phone for teleprompter display.
   * @param {string} text - Full script text
   * @param {number} fontSize - Font size in pt
   * @param {number} speed - Scroll speed multiplier
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  sendScript: (text, fontSize, speed) =>
    ipcRenderer.invoke('send-script', { text, fontSize, speed }),

  /**
   * Send a scroll position (0.0 = top, 1.0 = bottom) to the phone.
   * @param {number} percent - Scroll percentage 0.0–1.0
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  sendScrollPosition: (percent) =>
    ipcRenderer.invoke('send-scroll', percent),

  /**
   * Send a control command to the phone (START, PAUSE, RESET, etc.).
   * @param {string} command
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  sendCommand: (command) => ipcRenderer.invoke('send-command', command),

  // ── Voice sync ───────────────────────────────────────────────────────
  /**
   * Start voice-driven scroll sync.
   * @param {string} scriptText - The script to match against
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  startVoiceSync: (scriptText) =>
    ipcRenderer.invoke('start-voice-sync', scriptText),

  /**
   * Stop voice-driven scroll sync.
   * @returns {Promise<{success: boolean}>}
   */
  stopVoiceSync: () => ipcRenderer.invoke('stop-voice-sync'),

  // ── Virtual camera ───────────────────────────────────────────────────
  /**
   * Start the OBS Virtual Camera output (Windows only).
   * @param {number} width - Output width
   * @param {number} height - Output height
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  startVirtualCamera: (width, height) =>
    ipcRenderer.invoke('start-virtual-camera', { width, height }),

  /**
   * Stop the OBS Virtual Camera output.
   * @returns {Promise<{success: boolean}>}
   */
  stopVirtualCamera: () => ipcRenderer.invoke('stop-virtual-camera'),

  // ── Event listeners (main → renderer) ────────────────────────────────

  /**
   * Listen for video frames from the phone stream.
   * Callback receives a Buffer/Uint8Array of H.264 NAL data.
   */
  onStreamVideoFrame: (callback) => {
    const handler = (_event, data) => callback(data);
    ipcRenderer.on('stream-video-frame', handler);
    return () => ipcRenderer.removeListener('stream-video-frame', handler);
  },

  /**
   * Listen for audio frames from the phone stream.
   * Callback receives a Buffer/Uint8Array of AAC data.
   */
  onStreamAudioFrame: (callback) => {
    const handler = (_event, data) => callback(data);
    ipcRenderer.on('stream-audio-frame', handler);
    return () => ipcRenderer.removeListener('stream-audio-frame', handler);
  },

  /**
   * Listen for connection status changes.
   * Callback receives { status: 'connected'|'disconnected'|'connecting'|'error', message: string }
   */
  onConnectionStatus: (callback) => {
    const handler = (_event, data) => callback(data);
    ipcRenderer.on('connection-status', handler);
    return () => ipcRenderer.removeListener('connection-status', handler);
  },

  /**
   * Listen for discovered mDNS devices.
   * Callback receives an array of { name, host, port }.
   */
  onDiscoveredDevices: (callback) => {
    const handler = (_event, devices) => callback(devices);
    ipcRenderer.on('discovered-devices', handler);
    return () => ipcRenderer.removeListener('discovered-devices', handler);
  },

  /**
   * Listen for partial voice recognition results.
   * Callback receives the recognized text string.
   */
  onVoicePartial: (callback) => {
    const handler = (_event, text) => callback(text);
    ipcRenderer.on('voice-partial', handler);
    return () => ipcRenderer.removeListener('voice-partial', handler);
  },

  /**
   * Listen for voice-sync-driven scroll position updates.
   * Callback receives a float 0.0–1.0.
   */
  onVoiceScroll: (callback) => {
    const handler = (_event, percent) => callback(percent);
    ipcRenderer.on('voice-scroll', handler);
    return () => ipcRenderer.removeListener('voice-scroll', handler);
  },

  /**
   * Listen for match confidence updates (0.0–1.0).
   */
  onVoiceConfidence: (callback) => {
    const handler = (_event, confidence) => callback(confidence);
    ipcRenderer.on('voice-confidence', handler);
    return () => ipcRenderer.removeListener('voice-confidence', handler);
  },
});
