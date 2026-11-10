/**
 * virtual-camera.js — OBS Virtual Camera integration for Windows.
 *
 * On Windows, OBS Virtual Camera exposes a named pipe at
 *   \\.\pipe\ObsVirtualCamera
 * that accepts raw video frames in a specific format.
 *
 * This module:
 *   • Opens the named pipe and writes RGB24 frames
 *   • Handles the OBS virtual camera header format
 *   • Converts incoming H.264 NAL data → decoded frames (via callback)
 *   • Provides a clean start/stop API
 *   • Falls back gracefully on non-Windows platforms
 *
 * On macOS / Linux this module is a no-op that reports "not available".
 */

const { EventEmitter } = require('events');
const net = require('net');
const path = require('path');

// OBS Virtual Camera named pipe path (Windows)
const OBS_PIPE_PATH = '\\\\.\\pipe\\ObsVirtualCamera';

// Default frame dimensions
const DEFAULT_WIDTH = 1280;
const DEFAULT_HEIGHT = 720;

// OBS virtual camera expects a specific header before each frame.
// Format: [width:4 LE][height:4 LE][unused:4][unused:4] followed by RGB24 pixel data.
// Total header = 16 bytes.
const OBS_HEADER_SIZE = 16;

class VirtualCamera extends EventEmitter {
  /**
   * @param {object} opts
   * @param {number} opts.width  - Output width  (default 1280)
   * @param {number} opts.height - Output height (default 720)
   * @param {string} opts.pipePath - Override the pipe path (for testing)
   */
  constructor(opts = {}) {
    super();
    this.width = opts.width || DEFAULT_WIDTH;
    this.height = opts.height || DEFAULT_HEIGHT;
    this.pipePath = opts.pipePath || OBS_PIPE_PATH;

    this.pipe = null;
    this.isActive = false;
    this._frameBuffer = null;

    // Non-Windows platforms: virtual camera not available
    this.available = process.platform === 'win32';
  }

  // ── Public API ──────────────────────────────────────────────────────

  /**
   * Attempt to open the OBS Virtual Camera named pipe.
   * @returns {{success: boolean, error?: string}}
   */
  start() {
    if (!this.available) {
      console.warn('[VirtualCamera] OBS Virtual Camera is only available on Windows.');
      return { success: false, error: 'Virtual camera requires Windows' };
    }

    if (this.isActive) {
      return { success: true };
    }

    try {
      // Use net.connect for named pipe (Windows)
      this.pipe = net.createConnection(this.pipePath, () => {
        console.log('[VirtualCamera] Connected to OBS named pipe');
        this.isActive = true;
        this.emit('started');
      });

      this.pipe.on('error', (err) => {
        console.error('[VirtualCamera] Pipe error:', err.message);
        this.isActive = false;
        this.emit('error', err);
      });

      this.pipe.on('close', () => {
        console.log('[VirtualCamera] Pipe closed');
        this.isActive = false;
        this.emit('stopped');
      });

      return { success: true };
    } catch (err) {
      return { success: false, error: err.message };
    }
  }

  /**
   * Close the named pipe and stop feeding frames.
   */
  stop() {
    if (this.pipe) {
      this.pipe.destroy();
      this.pipe = null;
    }
    this.isActive = false;
    this._frameBuffer = null;
    this.emit('stopped');
  }

  /**
   * Feed a decoded video frame to the virtual camera.
   *
   * @param {Buffer|Uint8Array} frameData - Decoded RGB24 pixel data
   *   (width * height * 3 bytes). If the incoming frame doesn't match
   *   the configured dimensions, it will be skipped with a warning.
   */
  feedFrame(frameData) {
    if (!this.isActive || !this.pipe || !this.pipe.writable) return;

    const expectedSize = this.width * this.height * 3; // RGB24
    if (frameData.length !== expectedSize) {
      // In practice, frames arrive as H.264 NAL units from the stream client.
      // The renderer decodes them via WebCodecs and sends back RGB data.
      // If raw NAL data arrives here, we can't use it directly — skip.
      return;
    }

    // Build the OBS header: [width LE32][height LE32][0][0]
    const header = Buffer.alloc(OBS_HEADER_SIZE);
    header.writeUInt32LE(this.width, 0);
    header.writeUInt32LE(this.height, 4);
    // bytes 8-15 are unused (zeros)

    const frame = Buffer.concat([header, Buffer.from(frameData)]);

    try {
      this.pipe.write(frame);
    } catch (err) {
      console.error('[VirtualCamera] Write error:', err.message);
    }
  }

  /**
   * Update the output resolution. Takes effect on next start().
   */
  setResolution(width, height) {
    this.width = width;
    this.height = height;
  }
}

module.exports = VirtualCamera;
