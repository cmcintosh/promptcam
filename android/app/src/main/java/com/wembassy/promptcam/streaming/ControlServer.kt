package com.wembassy.promptcam.streaming

import android.util.Log
import com.wembassy.promptcam.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP server that receives teleprompter control messages from the desktop client.
 * 
 * Listens on a configurable port (default 8555) and parses the PromptCam binary protocol:
 * - 0x03: Teleprompter text (JSON {text, fontSize, speed})
 * - 0x04: Scroll position (4-byte float, 0.0 = top, 1.0 = bottom)
 * - 0x05: Control commands (UTF-8 string like "start", "stop", "ping")
 * 
 * Received messages are emitted via SharedFlows for the UI layer to consume.
 * 
 * @param port TCP port to listen on (default 8555)
 * @param scope Coroutine scope for launching connection handler
 */
class ControlServer(
    private val port: Int = Protocol.DEFAULT_CONTROL_PORT,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ControlServer"
        private const val SOCKET_BACKLOG = 2
        private const val MAX_MESSAGE_SIZE = 1024 * 1024  // 1MB safety limit
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var isRunning = false
    private var activeClient: Socket? = null

    // Reactive state
    private val _isRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _isRunning

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    // Emitted events

    /**
     * Teleprompter text update event.
     * Contains text content, font size, and scroll speed.
     */
    data class TeleprompterText(
        val text: String,
        val fontSize: Int,
        val speed: Float
    )

    private val _teleprompterText = MutableSharedFlow<TeleprompterText>(extraBufferCapacity = 16)
    val teleprompterText: SharedFlow<TeleprompterText> = _teleprompterText

    /**
     * Scroll position update event (0.0 = top, 1.0 = bottom).
     */
    private val _scrollPosition = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    val scrollPosition: SharedFlow<Float> = _scrollPosition

    /**
     * Control command event (raw command string).
     */
    private val _controlCommand = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val controlCommand: SharedFlow<String> = _controlCommand

    /**
     * Start the control server and begin listening for connections.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Control server already running")
            return
        }

        try {
            serverSocket = ServerSocket().apply {
                bind(InetSocketAddress(port))
                reuseAddress = true
            }
            isRunning = true
            _isRunning.value = true
            Log.i(TAG, "Control server listening on port $port")

            acceptJob = scope.launch(Dispatchers.IO) {
                acceptConnections()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start control server on port $port", e)
            isRunning = false
            _isRunning.value = false
            throw e
        }
    }

    /**
     * Accept loop for incoming control connections.
     * Typically only one desktop client connects.
     */
    private suspend fun acceptConnections() {
        while (isRunning && scope.isActive) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                Log.i(TAG, "Control client connected: ${clientSocket.inetAddress}")

                // Close any existing client (only one control connection at a time)
                activeClient?.let {
                    try { it.close() } catch (e: Exception) { }
                }

                activeClient = clientSocket
                _connected.value = true

                try {
                    clientSocket.keepAlive = true
                    clientSocket.tcpNoDelay = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set socket options", e)
                }

                // Handle this client's messages
                handleClient(clientSocket)

            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Error accepting control connection", e)
                }
            }
        }
    }

    /**
     * Read and parse messages from a connected control client.
     * Runs until the client disconnects or an error occurs.
     */
    private suspend fun handleClient(socket: Socket) {
        try {
            val inputStream = BufferedInputStream(socket.getInputStream())

            while (isRunning && scope.isActive && !socket.isClosed) {
                // Read 1-byte message type
                val typeByte = inputStream.read()
                if (typeByte == -1) {
                    Log.i(TAG, "Control client disconnected (EOF)")
                    break
                }

                val type = typeByte.toByte()

                when (type) {
                    Protocol.TYPE_TELEPROMPTER_TEXT -> {
                        // [0x03] [4-byte length] [UTF-8 JSON]
                        val length = readBigEndianInt(inputStream)
                        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                            Log.w(TAG, "Invalid teleprompter text length: $length")
                            break
                        }
                        val payload = ByteArray(length)
                        readFully(inputStream, payload)
                        parseTeleprompterText(payload)
                    }

                    Protocol.TYPE_SCROLL_POSITION -> {
                        // [0x04] [4-byte big-endian float]
                        val bits = readBigEndianInt(inputStream)
                        val position = java.lang.Float.intBitsToFloat(bits)
                        if (position in 0.0f..1.0f) {
                            _scrollPosition.tryEmit(position)
                        } else {
                            Log.w(TAG, "Scroll position out of range: $position")
                        }
                    }

                    Protocol.TYPE_CONTROL -> {
                        // [0x05] [4-byte length] [UTF-8 command string]
                        val length = readBigEndianInt(inputStream)
                        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                            Log.w(TAG, "Invalid control command length: $length")
                            break
                        }
                        val payload = ByteArray(length)
                        readFully(inputStream, payload)
                        val command = String(payload, Charsets.UTF_8)
                        Log.i(TAG, "Control command received: $command")
                        _controlCommand.tryEmit(command)

                        // Handle ping/pong automatically
                        if (command == Protocol.CMD_PING) {
                            sendPong(socket)
                        }
                    }

                    else -> {
                        Log.w(TAG, "Unknown message type: 0x${String.format("%02x", type)}")
                        // Try to recover by reading a length and skipping
                        val length = readBigEndianInt(inputStream)
                        if (length > 0 && length < MAX_MESSAGE_SIZE) {
                            inputStream.skip(length.toLong())
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Control client error: ${e.message}")
        } finally {
            _connected.value = false
            try { socket.close() } catch (e: Exception) { }
            if (activeClient === socket) {
                activeClient = null
            }
            Log.i(TAG, "Control client handler ended")
        }
    }

    /**
     * Parse teleprompter text JSON payload.
     * Expected format: {"text": "...", "fontSize": 24, "speed": 1.0}
     */
    private fun parseTeleprompterText(payload: ByteArray) {
        try {
            val json = JSONObject(String(payload, Charsets.UTF_8))
            val text = json.optString("text", "")
            val fontSize = json.optInt("fontSize", 24)
            val speed = json.optDouble("speed", 1.0).toFloat()

            _teleprompterText.tryEmit(TeleprompterText(text, fontSize, speed))
            Log.i(TAG, "Teleprompter text updated: ${text.length} chars, fontSize=$fontSize, speed=$speed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse teleprompter text JSON", e)
        }
    }

    /**
     * Send a pong response back to the client (for keepalive).
     */
    private fun sendPong(socket: Socket) {
        try {
            val pongData = Protocol.CMD_PONG.toByteArray(Charsets.UTF_8)
            val frame = Protocol.frame(Protocol.TYPE_CONTROL, pongData)
            socket.getOutputStream().apply {
                write(frame)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send pong", e)
        }
    }

    /**
     * Read a 4-byte big-endian integer from the input stream.
     */
    private fun readBigEndianInt(stream: BufferedInputStream): Int {
        val b0 = stream.read()
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        if (b0 == -1 || b1 == -1 || b2 == -1 || b3 == -1) {
            throw IOException("Unexpected EOF while reading 4-byte integer")
        }
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /**
     * Read exactly `data.size` bytes from the input stream.
     */
    private fun readFully(stream: BufferedInputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val read = stream.read(data, offset, data.size - offset)
            if (read == -1) {
                throw IOException("Unexpected EOF while reading payload")
            }
            offset += read
        }
    }

    /**
     * Stop the control server and close all connections.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        _isRunning.value = false
        _connected.value = false

        try { activeClient?.close() } catch (e: Exception) { }
        activeClient = null

        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null

        acceptJob?.cancel()
        acceptJob = null

        Log.i(TAG, "Control server stopped")
    }
}
