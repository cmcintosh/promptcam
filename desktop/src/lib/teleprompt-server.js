/**
 * teleprompt-server.js — TCP client that sends teleprompter commands
 * to the PromptCam phone app.
 *
 * Binary protocol (matches shared/protocol.js):
 *   Teleprompter text:  [0x03][4-byte BE length][UTF-8 JSON {text, fontSize, speed}]
 *   Scroll position:    [0x04][4-byte BE length][4-byte float BE, 0.0–1.0]
 *   Control command:    [0x05][4-byte BE length][UTF-8 command string]
 *
 * The phone listens for these on port 8555.
 */

const net = require('net');
const { EventEmitter } = require('events');

// Protocol message types
const MSG_TYPE_TELEPROMPT_TEXT = 0x03;
const MSG_TYPE_SCROLL_POSITION = 0x04;
const MSG_TYPE_CONTROL_COMMAND = 0x05;

// Reconnection settings
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_ATTEMPTS = 10;

class TelepromptServer extends EventEmitter {
  /**
   * @param {string} host - Phone IP address or hostname
   * @param {number} port - Teleprompter control port (default 8555)
   */
  constructor(host, port = 8555) {
    super();
    this.host = host;
    this.port = port;

    this.socket = null;
    this.connected = false;
    this.connecting = false;

    this._reconnectAttempts = 0;
    this._shouldReconnect = true;
    this._reconnectTimer = null;

    // Queue messages while disconnected so they aren't lost
    this._messageQueue = [];
  }

  // ── Public API ──────────────────────────────────────────────────────

  /**
   * Establish the TCP connection to the phone's teleprompter port.
   */
  connect() {
    if (this.connected || this.connecting) return;
    this.connecting = true;
    this._shouldReconnect = true;

    this.socket = net.createConnection(
      { host: this.host, port: this.port },
      () => {
        this.connected = true;
        this.connecting = false;
        this._reconnectAttempts = 0;
        console.log(`[TelepromptServer] Connected to ${this.host}:${this.port}`);
        this.emit('connected');

        // Flush queued messages
        if (this._messageQueue.length > 0) {
          console.log(`[TelepromptServer] Flushing ${this._messageQueue.length} queued messages`);
          for (const msg of this._messageQueue) {
            this._writeRaw(msg);
          }
          this._messageQueue = [];
        }
      },
    );

    this.socket.on('error', (err) => {
      console.error('[TelepromptServer] Socket error:', err.message);
      this.emit('error', err);
    });

    this.socket.on('close', () => this._onClose());
    this.socket.on('end', () => this._onClose());
  }

  /**
   * Send script text to the phone for teleprompter display.
   *
   * @param {string} text - Full script text
   * @param {number} fontSize - Font size in pt (default 24)
   * @param {number} speed - Scroll speed multiplier (default 1.0)
   * @returns {{success: boolean, error?: string}}
   */
  sendScript(text, fontSize = 24, speed = 1.0) {
    const payload = JSON.stringify({ text, fontSize, speed });
    const body = Buffer.from(payload, 'utf8');
    const msg = this._buildMessage(MSG_TYPE_TELEPROMPT_TEXT, body);
    return this._send(msg);
  }

  /**
   * Send a scroll position update to the phone.
   *
   * @param {number} percent - Scroll percentage 0.0 (top) to 1.0 (bottom)
   * @returns {{success: boolean, error?: string}}
   */
  sendScrollPosition(percent) {
    // Clamp to valid range
    const clamped = Math.max(0.0, Math.min(1.0, percent));
    const body = Buffer.alloc(4);
    body.writeFloatBE(clamped, 0);
    const msg = this._buildMessage(MSG_TYPE_SCROLL_POSITION, body);
    return this._send(msg);
  }

  /**
   * Send a control command to the phone.
   * Common commands: "START", "PAUSE", "RESET", "STOP".
   *
   * @param {string} command - The command string
   * @returns {{success: boolean, error?: string}}
   */
  sendCommand(command) {
    const body = Buffer.from(command, 'utf8');
    const msg = this._buildMessage(MSG_TYPE_CONTROL_COMMAND, body);
    return this._send(msg);
  }

  /**
   * Close the connection and stop reconnection attempts.
   */
  destroy() {
    this._shouldReconnect = false;
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
    this.connected = false;
    this.connecting = false;
    this._messageQueue = [];
  }

  // ── Internal helpers ────────────────────────────────────────────────

  /**
   * Build a protocol message: [type:1][length:4 BE][payload]
   * @param {number} type - Message type byte
   * @param {Buffer} payload - Message body
   * @returns {Buffer} Complete message
   */
  _buildMessage(type, payload) {
    const header = Buffer.alloc(5);
    header[0] = type;
    header.writeUInt32BE(payload.length, 1);
    return Buffer.concat([header, payload]);
  }

  /**
   * Send a raw message, queuing if not yet connected.
   * @param {Buffer} msg
   * @returns {{success: boolean, error?: string}}
   */
  _send(msg) {
    if (this.connected && this.socket && this.socket.writable) {
      this._writeRaw(msg);
      return { success: true };
    }

    // Queue for later — keep last 50 messages to avoid unbounded growth
    this._messageQueue.push(msg);
    if (this._messageQueue.length > 50) {
      this._messageQueue.shift();
    }
    return { success: false, error: 'Not connected — message queued' };
  }

  _writeRaw(msg) {
    try {
      this.socket.write(msg);
    } catch (err) {
      console.error('[TelepromptServer] Write error:', err.message);
    }
  }

  _onClose() {
    const wasConnected = this.connected;
    this.connected = false;
    this.connecting = false;
    this.socket = null;

    if (wasConnected) {
      console.log('[TelepromptServer] Disconnected');
      this.emit('disconnected');
    }

    if (this._shouldReconnect && this._reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
      this._reconnectAttempts++;
      const delay = RECONNECT_DELAY_MS * this._reconnectAttempts;
      console.log(
        `[TelepromptServer] Reconnecting in ${delay}ms (attempt ${this._reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`,
      );
      this._reconnectTimer = setTimeout(() => this.connect(), delay);
    } else if (this._shouldReconnect) {
      this.emit('error', new Error('Max reconnection attempts reached'));
    }
  }
}

module.exports = TelepromptServer;
