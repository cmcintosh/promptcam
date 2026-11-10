/**
 * stream-client.js — TCP client that connects to the PromptCam phone app
 * and receives a binary stream of H.264 video frames and AAC audio frames.
 *
 * Binary protocol (matches shared/protocol.js):
 *   Video frame:     [0x01][4-byte BE length][H.264 NAL data]
 *   Audio frame:     [0x02][4-byte BE length][AAC data]
 *
 * The client is an EventEmitter that emits:
 *   • 'connected'    — TCP connection established
 *   • 'disconnected' — TCP connection lost or closed
 *   • 'error'        (Error)  — a socket or protocol error
 *   • 'videoFrame'   (Buffer) — raw H.264 NAL unit data
 *   • 'audioFrame'   (Buffer) — raw AAC frame data
 */

const net = require('net');
const { EventEmitter } = require('events');

// Protocol constants — must match shared/protocol.js
const MSG_TYPE_VIDEO = 0x01;
const MSG_TYPE_AUDIO = 0x02;
const HEADER_SIZE = 5; // 1 byte type + 4 bytes length

// Reconnection settings
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_ATTEMPTS = 10;

class StreamClient extends EventEmitter {
  /**
   * @param {string} host - Phone IP address or hostname
   * @param {number} port - TCP port (default 8554)
   */
  constructor(host, port = 8554) {
    super();
    this.host = host;
    this.port = port;

    this.socket = null;
    this.connected = false;
    this.connecting = false;

    // Receive buffer for accumulating partial protocol messages
    this._buffer = Buffer.alloc(0);
    this._expectingPayload = false;
    this._msgType = 0;
    this._msgLength = 0;

    // Reconnection state
    this._reconnectAttempts = 0;
    this._shouldReconnect = true;
    this._reconnectTimer = null;
  }

  // ── Public API ──────────────────────────────────────────────────────

  /**
   * Establish the TCP connection. Safe to call multiple times —
   * if already connected, this is a no-op.
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
        console.log(`[StreamClient] Connected to ${this.host}:${this.port}`);
        this.emit('connected');
      },
    );

    this.socket.on('data', (data) => this._onData(data));
    this.socket.on('error', (err) => this._onError(err));
    this.socket.on('close', () => this._onClose());
    this.socket.on('end', () => this._onClose());
  }

  /**
   * Gracefully close the connection and stop reconnection attempts.
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
    this._buffer = Buffer.alloc(0);
  }

  // ── Internal: data parsing ───────────────────────────────────────────

  /**
   * Accumulate incoming data and extract complete protocol messages.
   * Each message has a 5-byte header: [type:1][length:4 big-endian],
   * followed by `length` bytes of payload.
   */
  _onData(data) {
    this._buffer = Buffer.concat([this._buffer, data]);

    // Process as many complete messages as are available
    while (this._buffer.length >= HEADER_SIZE) {
      // If we haven't started parsing a message yet, read the header
      if (!this._expectingPayload) {
        this._msgType = this._buffer[0];
        this._msgLength = this._buffer.readUInt32BE(1);
        this._expectingPayload = true;

        // Sanity check: reject absurdly large frames (> 16 MB)
        if (this._msgLength > 16 * 1024 * 1024) {
          console.warn(
            `[StreamClient] Suspicious frame size ${this._msgLength}, resetting buffer`,
          );
          this._buffer = Buffer.alloc(0);
          this._expectingPayload = false;
          break;
        }
      }

      // Wait until we have the full payload
      const totalNeeded = HEADER_SIZE + this._msgLength;
      if (this._buffer.length < totalNeeded) {
        break;
      }

      // Extract payload
      const payload = this._buffer.subarray(HEADER_SIZE, totalNeeded);

      // Dispatch
      switch (this._msgType) {
        case MSG_TYPE_VIDEO:
          this.emit('videoFrame', payload);
          break;
        case MSG_TYPE_AUDIO:
          this.emit('audioFrame', payload);
          break;
        default:
          console.warn(`[StreamClient] Unknown message type 0x${this._msgType.toString(16)}`);
      }

      // Remove processed bytes and reset for next message
      this._buffer = this._buffer.subarray(totalNeeded);
      this._expectingPayload = false;
    }
  }

  // ── Internal: error / close handling ─────────────────────────────────

  _onError(err) {
    console.error(`[StreamClient] Socket error: ${err.message}`);
    this.emit('error', err);
  }

  _onClose() {
    const wasConnected = this.connected;
    this.connected = false;
    this.connecting = false;
    this.socket = null;
    this._buffer = Buffer.alloc(0);
    this._expectingPayload = false;

    if (wasConnected) {
      console.log('[StreamClient] Disconnected');
      this.emit('disconnected');
    }

    // Attempt reconnection
    if (this._shouldReconnect && this._reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
      this._reconnectAttempts++;
      const delay = RECONNECT_DELAY_MS * this._reconnectAttempts; // back off
      console.log(
        `[StreamClient] Reconnecting in ${delay}ms (attempt ${this._reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`,
      );
      this._reconnectTimer = setTimeout(() => this.connect(), delay);
    } else if (this._shouldReconnect) {
      this.emit(
        'error',
        new Error(`Max reconnection attempts (${MAX_RECONNECT_ATTEMPTS}) reached`),
      );
    }
  }
}

module.exports = StreamClient;
