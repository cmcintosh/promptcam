package com.wembassy.promptcam.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.json.JSONObject

/**
 * Binary protocol constants and helpers shared between Android app and desktop clients.
 *
 * Standard message framing:
 *   [1-byte type] [4-byte big-endian length] [payload]
 *
 * Exception: SCROLL_POSITION (0x04) uses a 4-byte IEEE 754 float instead of length-prefixed data:
 *   [1-byte type] [4-byte big-endian float]
 *
 * This must match shared/protocol.js in the PromptCam desktop client exactly.
 */
object Protocol {

    // Message type constants (as Int for convenience in Kotlin)
    const val VIDEO_FRAME: Int = 0x01
    const val AUDIO_FRAME: Int = 0x02
    const val TELEPROMPTER_TEXT: Int = 0x03
    const val SCROLL_POSITION: Int = 0x04
    const val CONTROL_COMMAND: Int = 0x05

    // Default ports
    const val DEFAULT_VIDEO_PORT = 8554
    const val DEFAULT_CONTROL_PORT = 8555

    // mDNS service names
    const val SERVICE_NAME_VIDEO = "promptcam"
    const val SERVICE_NAME_CONTROL = "promptcam-tp"
    const val SERVICE_TYPE = "_tcp"

    // Control command strings (sent as UTF-8 in CONTROL_COMMAND messages)
    const val CMD_START = "start"
    const val CMD_STOP = "stop"
    const val CMD_PING = "ping"
    const val CMD_PONG = "pong"
    const val CMD_RESET = "reset"
    const val CMD_SWITCH_CAMERA = "switch_camera"

    // Maximum message size for safety (1 MB)
    private const val MAX_MESSAGE_SIZE = 1024 * 1024

    /**
     * Write a standard framed message to a DataOutputStream.
     * Format: [type:1 byte][length:4 bytes Big-Endian][payload]
     *
     * @param dos The DataOutputStream to write to
     * @param type Message type (0x01-0x05)
     * @param payload Message payload bytes
     * @throws IOException If an I/O error occurs
     */
    @Throws(IOException::class)
    fun writeMessage(dos: DataOutputStream, type: Int, payload: ByteArray) {
        dos.writeByte(type)
        dos.writeInt(payload.size) // DataOutputStream.writeInt is big-endian
        if (payload.isNotEmpty()) {
            dos.write(payload)
        }
        dos.flush()
    }

    /**
     * Write a scroll position message (special format — no length prefix).
     * Format: [0x04][4-byte IEEE 754 float Big-Endian]
     *
     * @param dos The DataOutputStream to write to
     * @param percent Scroll position (0.0 = top, 1.0 = bottom)
     * @throws IOException If an I/O error occurs
     */
    @Throws(IOException::class)
    fun writeScrollPosition(dos: DataOutputStream, percent: Float) {
        dos.writeByte(SCROLL_POSITION)
        // Write float as big-endian IEEE 754
        val bits = java.lang.Float.floatToRawIntBits(percent)
        dos.writeByte((bits shr 24) and 0xFF)
        dos.writeByte((bits shr 16) and 0xFF)
        dos.writeByte((bits shr 8) and 0xFF)
        dos.writeByte(bits and 0xFF)
        dos.flush()
    }

    /**
     * Read a standard framed message from a DataInputStream.
     * Format: [type:1 byte][length:4 bytes Big-Endian][payload]
     *
     * @param dis The DataInputStream to read from
     * @return Pair of (messageType, payload) or null if EOF
     * @throws IOException If an I/O error occurs or message is too large
     */
    @Throws(IOException::class)
    fun readMessage(dis: DataInputStream): Pair<Int, ByteArray>? {
        val type = dis.readByte().toInt() and 0xFF
        val length = dis.readInt() // DataInputStream.readInt is big-endian

        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            throw IOException("Invalid message length: $length")
        }

        val payload = ByteArray(length)
        dis.readFully(payload)
        return Pair(type, payload)
    }

    /**
     * Read a scroll position message (special format — no length prefix).
     * Format: [0x04][4-byte IEEE 754 float Big-Endian]
     * Assumes the type byte (0x04) has already been read by the caller.
     *
     * @param dis The DataInputStream to read from (positioned after the type byte)
     * @return Scroll position as a Float (0.0 to 1.0)
     * @throws IOException If an I/O error occurs
     */
    @Throws(IOException::class)
    fun readScrollPosition(dis: DataInputStream): Float {
        val bits = dis.readInt() // big-endian
        return java.lang.Float.intBitsToFloat(bits)
    }

    /**
     * Read a 4-byte big-endian integer from a DataInputStream.
     *
     * @param dis The DataInputStream to read from
     * @return The integer value
     * @throws IOException If an I/O error occurs
     */
    @Throws(IOException::class)
    fun readBigEndianInt(dis: DataInputStream): Int {
        return dis.readInt()
    }

    /**
     * Parse teleprompter text JSON payload.
     * Expected format: {"text":"...","fontSize":24,"speed":1.0}
     *
     * @param payload UTF-8 encoded JSON bytes
     * @return TeleprompterData data class, or null if parsing fails
     */
    fun parseTeleprompterJson(payload: ByteArray): TeleprompterData? {
        return try {
            val json = JSONObject(String(payload, Charsets.UTF_8))
            TeleprompterData(
                text = json.optString("text", ""),
                fontSize = json.optInt("fontSize", 24),
                speed = json.optDouble("speed", 1.0).toFloat()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Serialize teleprompter data to JSON payload bytes.
     *
     * @param text The teleprompter text
     * @param fontSize Font size in sp
     * @param speed Scroll speed multiplier
     * @return UTF-8 encoded JSON bytes
     */
    fun serializeTeleprompterJson(text: String, fontSize: Int, speed: Float): ByteArray {
        val json = JSONObject()
        json.put("text", text)
        json.put("fontSize", fontSize)
        json.put("speed", speed.toDouble())
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Build a framed message as a ByteArray (for buffering or batching).
     * Format: [type:1 byte][length:4 bytes Big-Endian][payload]
     *
     * @param type Message type
     * @param payload Message payload
     * @return Framed message bytes
     */
    fun frameMessage(type: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = type.toByte()
        val len = payload.size
        frame[1] = ((len shr 24) and 0xFF).toByte()
        frame[2] = ((len shr 16) and 0xFF).toByte()
        frame[3] = ((len shr 8) and 0xFF).toByte()
        frame[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        return frame
    }

    /**
     * Build a scroll position message as a ByteArray.
     * Format: [0x04][4-byte big-endian float]
     *
     * @param position Scroll position (0.0 = top, 1.0 = bottom)
     * @return Framed message bytes
     */
    fun frameScrollPosition(position: Float): ByteArray {
        val frame = ByteArray(5)
        frame[0] = SCROLL_POSITION.toByte()
        val bits = java.lang.Float.floatToRawIntBits(position)
        frame[1] = ((bits shr 24) and 0xFF).toByte()
        frame[2] = ((bits shr 16) and 0xFF).toByte()
        frame[3] = ((bits shr 8) and 0xFF).toByte()
        frame[4] = (bits and 0xFF).toByte()
        return frame
    }
}

/**
 * Parsed teleprompter text data from a 0x03 message.
 */
data class TeleprompterData(
    val text: String,
    val fontSize: Int,
    val speed: Float
)
