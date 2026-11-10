package com.wembassy.promptcam.network

import android.util.Log
import com.wembassy.promptcam.protocol.Protocol
import com.wembassy.promptcam.protocol.TeleprompterData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP server on port 8555 that receives teleprompter control messages
 * from the desktop client.
 *
 * Listens on a configurable port (default 8555) and parses the PromptCam binary protocol:
 * - 0x03: Teleprompter text (JSON: {"text":"...","fontSize":24,"speed":1.0})
 * - 0x04: Scroll position (4-byte IEEE 754 float, 0.0 = top, 1.0 = bottom)
 * - 0x05: Control commands (UTF-8 string like "start", "stop", "ping")
 *
 * Received messages are exposed via StateFlows and SharedFlows for the UI
 * layer to consume. The teleprompter text and scroll position are exposed
 * as MutableStateFlow so the UI can observe them reactively.
 *
 * @param port TCP port to listen on (default 8555)
 * @param scope Coroutine scope for background operations
 */
class ControlServer(
    private val port: Int = Protocol.DEFAULT_CONTROL_PORT,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ControlServer"
        private const val SOCKET_BACKLOG = 2
        private const val MAX_MESSAGE_SIZE = 1024 * 1024 // 1 MB safety limit
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var isRunning = false
    private var activeClient: Socket? = null

    // Reactive state for UI
    private val _isRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Teleprompter text exposed as StateFlow for UI observation
    private val _teleprompterText = MutableStateFlow("")
    val teleprompterText: StateFlow<String> = _teleprompterText.asStateFlow()

    // Scroll position exposed as StateFlow for UI observation (0.0 = top, 1.0 = bottom)
    private val _scrollPosition = MutableStateFlow(0.0f)
    val scrollPosition: StateFlow<Float> = _scrollPosition.asStateFlow()

    // Font size for teleprompter display
    private val _fontSize = MutableStateFlow(24)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    // Scroll speed multiplier
    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    // Control command events
    private val _controlCommand = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val controlCommand: SharedFlow<String> = _controlCommand.asSharedFlow()

    /**
     * Callback interface for control command dispatch.
     * Implement this to handle control commands like "start", "stop", "switch_camera".
     */
    interface ControlCallback {
        fun onControlCommand(command: String)
        fun onTeleprompterText(data: TeleprompterData)
        fun onScrollPosition(position: Float)
    }

    var callback: ControlCallback? = null

    /**
     * Start the control server and begin listening for connections.
     * @throws IOException if the server socket cannot be bound
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
     * Typically only one desktop client connects at a time.
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
        val dis = DataInputStream(BufferedInputStream(socket.getInputStream()))

        try {
            while (isRunning && scope.isActive && !socket.isClosed) {
                // Read 1-byte message type
                val typeByte = dis.read()
                if (typeByte == -1) {
                    Log.i(TAG, "Control client disconnected (EOF)")
                    break
                }

                val type = typeByte and 0xFF

                when (type) {
                    Protocol.TELEPROMPTER_TEXT -> {
                        // [0x03] [4-byte big-endian length] [UTF-8 JSON]
                        val length = dis.readInt()
                        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                            Log.w(TAG, "Invalid teleprompter text length: $length")
                            break
                        }
                        val payload = ByteArray(length)
                        dis.readFully(payload)
                        parseTeleprompterText(payload)
                    }

                    Protocol.SCROLL_POSITION -> {
                        // [0x04] [4-byte big-endian float] — NO length prefix
                        val position = Protocol.readScrollPosition(dis)
                        if (position in 0.0f..1.0f) {
                            _scrollPosition.value = position
                            callback?.onScrollPosition(position)
                        } else {
                            Log.w(TAG, "Scroll position out of range: $position")
                        }
                    }

                    Protocol.CONTROL_COMMAND -> {
                        // [0x05] [4-byte big-endian length] [UTF-8 command string]
                        val length = dis.readInt()
                        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                            Log.w(TAG, "Invalid control command length: $length")
                            break
                        }
                        val payload = ByteArray(length)
                        dis.readFully(payload)
                        val command = String(payload, Charsets.UTF_8)
                        Log.i(TAG, "Control command received: $command")
                        _controlCommand.tryEmit(command)
                        callback?.onControlCommand(command)

                        // Handle ping/pong automatically
                        if (command == Protocol.CMD_PING) {
                            sendPong(socket)
                        }
                    }

                    else -> {
                        Log.w(TAG, "Unknown message type: 0x${String.format("%02x", type)}")
                        // Try to recover by reading a length and skipping
                        val length = dis.readInt()
                        if (length > 0 && length < MAX_MESSAGE_SIZE) {
                            dis.skipBytes(length)
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
     * Parse teleprompter text JSON payload and update state.
     * Expected format: {"text":"...","fontSize":24,"speed":1.0}
     */
    private fun parseTeleprompterText(payload: ByteArray) {
        val data = Protocol.parseTeleprompterJson(payload)
        if (data != null) {
            _teleprompterText.value = data.text
            _fontSize.value = data.fontSize
            _speed.value = data.speed
            callback?.onTeleprompterText(data)
            Log.i(TAG, "Teleprompter text updated: ${data.text.length} chars, fontSize=${data.fontSize}, speed=${data.speed}")
        } else {
            Log.e(TAG, "Failed to parse teleprompter text JSON")
        }
    }

    /**
     * Send a pong response back to the client (for keepalive).
     */
    private fun sendPong(socket: Socket) {
        try {
            val pongData = Protocol.CMD_PONG.toByteArray(Charsets.UTF_8)
            val dos = DataOutputStream(socket.getOutputStream())
            Protocol.writeMessage(dos, Protocol.CONTROL_COMMAND, pongData)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send pong", e)
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
