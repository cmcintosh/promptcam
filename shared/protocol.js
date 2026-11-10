/**
 * PromptCam Binary Protocol — shared between Android (Kotlin) and Desktop (Node.js)
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │                          PROMPTCAM BINARY PROTOCOL                           │
 * │                     Compatible with Kotlin / Java DataInputStream             │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │                                                                              │
 * │  Every message has the following wire format:                                │
 * │                                                                              │
 * │  ┌──────────┬──────────────┬───────────────────────────────────────────┐    │
 * │  │ Type (1) │ Length (4)   │ Payload (variable, Length bytes)          │    │
 * │  │ byte     │ Big-Endian   │                                           │    │
 * │  └──────────┴──────────────┴───────────────────────────────────────────┘    │
 * │                                                                              │
 * │  EXCEPTION: SCROLL_POSITION (0x04) has no length field — the payload        │
 * │  is always exactly 4 bytes (IEEE 754 float). Wire format:                   │
 * │                                                                              │
 * │  ┌──────────┬──────────────────────────────────────┐                       │
 * │  │ Type (1) │ Percent (4 bytes, IEEE 754 float BE) │                       │
 * │  └──────────┴──────────────────────────────────────┘                       │
 * │                                                                              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  Message Types                                                               │
 * │                                                                              │
 * │  0x01  VIDEO_FRAME       H.264 NAL units (Annex B or AVCC)                  │
 * │  0x02  AUDIO_FRAME       AAC raw data (ADTS frames)                         │
 * │  0x03  TELEPROMPTER_TEXT JSON UTF-8: {text, fontSize, speed}               │
 * │  0x04  SCROLL_POSITION   4-byte IEEE 754 float (0.0–1.0), no length prefix │
 * │  0x05  CONTROL_COMMAND   UTF-8 command string                               │
 * │                                                                              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  Kotlin Implementation Notes                                                 │
 * │                                                                              │
 * │  Use DataInputStream / DataOutputStream for reading/writing:                │
 * │                                                                              │
 * │  Sending:                                                                    │
 * │    dos.writeByte(type);                                                     │
 * │    if (type == SCROLL_POSITION) {                                           │
 * │        dos.writeFloat(percent);                                             │
 * │    } else {                                                                  │
 * │        dos.writeInt(payload.size);  // Big-Endian                            │
 * │        dos.write(payload);                                                  │
 * │    }                                                                         │
 * │    dos.flush();                                                              │
 * │                                                                              │
 * │  Receiving:                                                                  │
 * │    int type = dis.readByte() & 0xFF;                                        │
 * │    if (type == SCROLL_POSITION) {                                           │
 * │        float percent = dis.readFloat();                                     │
 * │    } else {                                                                  │
 * │        int len = dis.readInt();                                             │
 * │        byte[] payload = new byte[len];                                      │
 * │        dis.readFully(payload);                                              │
 * │    }                                                                         │
 * │                                                                              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  Port Allocation                                                             │
 * │                                                                              │
 * │  Port 8554 — Audio/Video stream (VIDEO_FRAME, AUDIO_FRAME)                  │
 * │  Port 8555 — Control channel (TELEPROMPTER_TEXT, SCROLL_POSITION,          │
 * │               CONTROL_COMMAND)                                               │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 */

'use strict';

/**
 * Message type constants.
 * @readonly
 * @enum {number}
 */
const MessageType = {
  VIDEO_FRAME: 0x01,
  AUDIO_FRAME: 0x02,
  TELEPROMPTER_TEXT: 0x03,
  SCROLL_POSITION: 0x04,
  CONTROL_COMMAND: 0x05,
};

// ─── Internal helpers ──────────────────────────────────────────────────────────

/**
 * Encode a variable-length payload message: [type][4-byte BE length][data]
 * @param {number} type - One of MessageType
 * @param {Buffer} data - Payload buffer
 * @returns {Buffer} Encoded message
 */
function encodeLengthPrefixed(type, data) {
  const header = Buffer.alloc(5); // 1 byte type + 4 bytes length
  header.writeUInt8(type, 0);
  header.writeUInt32BE(data.length, 1);
  return Buffer.concat([header, data]);
}

/**
 * Read a 4-byte Big-Endian float from a buffer at the given offset.
 * @param {Buffer} buf
 * @param {number} offset
 * @returns {number}
 */
function readFloatBE(buf, offset) {
  return buf.readFloatBE(offset);
}

// ─── Public encode functions ───────────────────────────────────────────────────

/**
 * Encode an H.264 NAL unit as a VIDEO_FRAME message.
 * @param {Buffer|Uint8Array} nalData - Raw H.264 NAL data
 * @returns {Buffer} [0x01][4-byte BE length][nalData]
 */
function encodeVideoFrame(nalData) {
  return encodeLengthPrefixed(MessageType.VIDEO_FRAME, Buffer.from(nalData));
}

/**
 * Encode AAC audio data as an AUDIO_FRAME message.
 * @param {Buffer|Uint8Array} aacData - Raw AAC frame data
 * @returns {Buffer} [0x02][4-byte BE length][aacData]
 */
function encodeAudioFrame(aacData) {
  return encodeLengthPrefixed(MessageType.AUDIO_FRAME, Buffer.from(aacData));
}

/**
 * Encode teleprompter text as a TELEPROMPTER_TEXT message.
 * The payload is UTF-8 JSON: {"text":"...","fontSize":24,"speed":1.0}
 * @param {string} text - The script text to display
 * @param {number} fontSize - Font size in sp (default 24)
 * @param {number} speed - Scroll speed multiplier (default 1.0)
 * @returns {Buffer} [0x03][4-byte BE length][JSON UTF-8]
 */
function encodeTeleprompterText(text, fontSize = 24, speed = 1.0) {
  const json = JSON.stringify({ text, fontSize, speed });
  const data = Buffer.from(json, 'utf8');
  return encodeLengthPrefixed(MessageType.TELEPROMPTER_TEXT, data);
}

/**
 * Encode a scroll position as a SCROLL_POSITION message.
 * Wire format: [0x04][4-byte IEEE 754 float BE]  (no length prefix)
 * @param {number} percent - Scroll percentage 0.0 to 1.0
 * @returns {Buffer} [0x04][float BE]
 */
function encodeScrollPosition(percent) {
  // Clamp to [0.0, 1.0]
  const clamped = Math.max(0.0, Math.min(1.0, percent));
  const buf = Buffer.alloc(5); // 1 byte type + 4 bytes float
  buf.writeUInt8(MessageType.SCROLL_POSITION, 0);
  buf.writeFloatBE(clamped, 1);
  return buf;
}

/**
 * Encode a control command as a CONTROL_COMMAND message.
 * @param {string} command - The command string (e.g. "START", "STOP", "PAUSE")
 * @returns {Buffer} [0x05][4-byte BE length][command UTF-8]
 */
function encodeCommand(command) {
  const data = Buffer.from(command, 'utf8');
  return encodeLengthPrefixed(MessageType.CONTROL_COMMAND, data);
}

// ─── Public decode functions ───────────────────────────────────────────────────

/**
 * Parse a single complete message from a buffer.
 * Assumes the buffer starts at the beginning of a message and contains
 * at least one full message. For streaming/chunked data use createMessageReader().
 *
 * @param {Buffer} buffer - Buffer containing one complete message
 * @returns {{type: number, data: Buffer, consumed: number}} Parsed message
 * @throws {Error} if buffer is too short for a complete message
 */
function parseMessage(buffer) {
  if (buffer.length < 1) {
    throw new Error('Buffer too short: no message type byte');
  }

  const type = buffer.readUInt8(0);

  if (type === MessageType.SCROLL_POSITION) {
    // SCROLL_POSITION has no length prefix — payload is always 4-byte float
    if (buffer.length < 5) {
      throw new Error('Buffer too short for SCROLL_POSITION (need 5 bytes, have ' + buffer.length + ')');
    }
    const percent = readFloatBE(buffer, 1);
    return { type, data: percent, consumed: 5 };
  }

  // All other types: [type][4-byte BE length][data]
  if (buffer.length < 5) {
    throw new Error('Buffer too short for header (need 5 bytes, have ' + buffer.length + ')');
  }

  const length = buffer.readUInt32BE(1);
  const totalNeeded = 5 + length;

  if (buffer.length < totalNeeded) {
    throw new Error('Buffer too short for payload (need ' + totalNeeded + ' bytes, have ' + buffer.length + ')');
  }

  const data = buffer.slice(5, totalNeeded);
  return { type, data, consumed: totalNeeded };
}

/**
 * Create a stateful stream reader that handles partial messages across
 * TCP chunk boundaries. The reader buffers incoming data and invokes the
 * provided callback for every complete message parsed.
 *
 * @param {function({type: number, data: Buffer|number}): void} onMessage
 *   Callback invoked with each complete message. For SCROLL_POSITION,
 *   `data` is a number (float); for all other types, `data` is a Buffer.
 * @returns {{feed: function(Buffer): void, reset: function(): void}}
 *   - feed(chunk): Push a Buffer chunk into the reader.
 *   - reset(): Clear internal buffer (e.g. on reconnect).
 */
function createMessageReader(onMessage) {
  let buffer = Buffer.alloc(0);

  /**
   * Attempt to parse as many complete messages as possible from the
   * internal buffer. Leftover partial bytes are retained.
   */
  function tryParse() {
    while (buffer.length >= 1) {
      const type = buffer.readUInt8(0);

      if (type === MessageType.SCROLL_POSITION) {
        // SCROLL_POSITION: [0x04][4-byte float] — 5 bytes total, no length prefix
        if (buffer.length < 5) {
          break; // Need more data
        }
        const percent = readFloatBE(buffer, 1);
        onMessage({ type: MessageType.SCROLL_POSITION, data: percent });
        buffer = buffer.slice(5);
        continue;
      }

      // Standard: [type][4-byte BE length][payload]
      if (buffer.length < 5) {
        break; // Need at least the header
      }

      const payloadLength = buffer.readUInt32BE(1);
      const totalNeeded = 5 + payloadLength;

      if (buffer.length < totalNeeded) {
        break; // Need more data for the full payload
      }

      const payload = buffer.slice(5, totalNeeded);
      onMessage({ type, data: payload });
      buffer = buffer.slice(totalNeeded);
    }
  }

  return {
    /**
     * Feed a new chunk of data into the reader.
     * @param {Buffer} chunk
     */
    feed(chunk) {
      buffer = Buffer.concat([buffer, Buffer.from(chunk)]);
      tryParse();
    },

    /**
     * Reset the reader, discarding any buffered partial messages.
     * Call this on reconnect or when the stream is corrupted.
     */
    reset() {
      buffer = Buffer.alloc(0);
    },
  };
}

// ─── Exports ───────────────────────────────────────────────────────────────────

module.exports = {
  MessageType,
  encodeVideoFrame,
  encodeAudioFrame,
  encodeTeleprompterText,
  encodeScrollPosition,
  encodeCommand,
  parseMessage,
  createMessageReader,
};
