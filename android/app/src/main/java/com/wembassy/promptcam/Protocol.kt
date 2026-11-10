package com.wembassy.promptcam

/**
 * Binary protocol constants shared between the Android app and desktop clients.
 * 
 * All messages use a simple framing format:
 *   [1-byte type] [4-byte big-endian length] [payload]
 * 
 * Exception: scroll position (0x04) uses a 4-byte float instead of length-prefixed data.
 * 
 * This must match shared/protocol.js in the PromptCam desktop client.
 */
object Protocol {

    // Message type bytes
    const val TYPE_VIDEO: Byte = 0x01
    const val TYPE_AUDIO: Byte = 0x02
    const val TYPE_TELEPROMPTER_TEXT: Byte = 0x03
    const val TYPE_SCROLL_POSITION: Byte = 0x04
    const val TYPE_CONTROL: Byte = 0x05

    // Default ports
    const val DEFAULT_VIDEO_PORT = 8554
    const val DEFAULT_CONTROL_PORT = 8555

    // mDNS service type
    const val SERVICE_TYPE = "_tcp"
    const val SERVICE_NAME = "promptcam"

    // Control commands (sent as UTF-8 strings with type 0x05)
    const val CMD_START: String = "start"
    const val CMD_STOP: String = "stop"
    const val CMD_PING: String = "ping"
    const val CMD_PONG: String = "pong"
    const val CMD_RESET: String = "reset"

    /**
     * Build a framed message: [type] [4-byte big-endian length] [payload]
     */
    fun frame(type: Byte, payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = type
        // Write 4-byte big-endian length
        val len = payload.size
        frame[1] = ((len shr 24) and 0xFF).toByte()
        frame[2] = ((len shr 16) and 0xFF).toByte()
        frame[3] = ((len shr 8) and 0xFF).toByte()
        frame[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        return frame
    }

    /**
     * Build a scroll position message: [0x04] [4-byte big-endian float]
     * Note: scroll position uses a fixed 4-byte float, not length-prefixed.
     */
    fun frameScrollPosition(position: Float): ByteArray {
        val frame = ByteArray(5)
        frame[0] = TYPE_SCROLL_POSITION
        // Convert float to raw int bits, then write big-endian
        val bits = java.lang.Float.floatToRawIntBits(position)
        frame[1] = ((bits shr 24) and 0xFF).toByte()
        frame[2] = ((bits shr 16) and 0xFF).toByte()
        frame[3] = ((bits shr 8) and 0xFF).toByte()
        frame[4] = (bits and 0xFF).toByte()
        return frame
    }
}
