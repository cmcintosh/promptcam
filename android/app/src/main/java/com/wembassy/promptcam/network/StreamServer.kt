package com.wembassy.promptcam.network

import android.util.Log
import com.wembassy.promptcam.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP server on port 8554 that streams H.264 video and AAC audio frames
 * to connected desktop clients.
 *
 * This server:
 * - Listens on a configurable port (default 8554)
 * - Accepts multiple client connections simultaneously
 * - Sends framed A/V data using the PromptCam binary protocol:
 *     Video: [0x01] [4-byte big-endian length] [H.264 NAL data]
 *     Audio: [0x02] [4-byte big-endian length] [AAC data with ADTS]
 * - Handles client disconnection gracefully
 * - Emits connection count via a StateFlow for UI updates
 *
 * The server broadcasts frames to all connected clients.
 * Uses DataOutputStream for writing the binary protocol.
 *
 * @param port TCP port to listen on (default 8554)
 * @param scope Coroutine scope for background operations
 */
class StreamServer(
    private val port: Int = Protocol.DEFAULT_VIDEO_PORT,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "StreamServer"
        private const val SOCKET_BACKLOG = 5
    }

    // Server state
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var isRunning = false

    // Connected clients — thread-safe for concurrent access
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    /**
     * Represents a connected client with its socket and output stream.
     */
    data class ClientConnection(
        val socket: Socket,
        val outputStream: DataOutputStream,
        val id: String
    )

    // Reactive state for UI
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Start the TCP server and begin accepting connections.
     * @throws IOException if the server socket cannot be bound
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Server already running")
            return
        }

        try {
            serverSocket = ServerSocket().apply {
                bind(InetSocketAddress(port))
                reuseAddress = true
            }
            isRunning = true
            _isRunning.value = true
            Log.i(TAG, "Stream server listening on port $port")

            acceptJob = scope.launch(Dispatchers.IO) {
                acceptConnections()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start stream server on port $port", e)
            isRunning = false
            _isRunning.value = false
            throw e
        }
    }

    /**
     * Accept loop — runs in a coroutine, accepting client connections.
     */
    private suspend fun acceptConnections() {
        while (isRunning && scope.isActive) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                Log.i(TAG, "Client connected: ${clientSocket.inetAddress}")

                try {
                    clientSocket.keepAlive = true
                    clientSocket.tcpNoDelay = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set socket options", e)
                }

                val client = ClientConnection(
                    socket = clientSocket,
                    outputStream = DataOutputStream(clientSocket.getOutputStream()),
                    id = "${clientSocket.inetAddress}:${clientSocket.port}"
                )
                clients.add(client)
                _clientCount.value = clients.size
                Log.i(TAG, "Client added: ${client.id}. Total: ${clients.size}")

            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Error accepting client connection", e)
                }
            }
        }
    }

    /**
     * Send a video frame (H.264 NAL unit) to all connected clients.
     * Uses the framing protocol: [0x01] [4-byte big-endian length] [NAL data]
     *
     * @param data Raw H.264 NAL unit data from the encoder
     */
    suspend fun sendVideoFrame(data: ByteArray) {
        if (clients.isEmpty()) return
        broadcast(Protocol.VIDEO_FRAME, data)
    }

    /**
     * Send an audio frame (AAC data with ADTS) to all connected clients.
     * Uses the framing protocol: [0x02] [4-byte big-endian length] [AAC data]
     *
     * @param data AAC frame data from the encoder (with ADTS header)
     */
    suspend fun sendAudioFrame(data: ByteArray) {
        if (clients.isEmpty()) return
        broadcast(Protocol.AUDIO_FRAME, data)
    }

    /**
     * Broadcast a framed message to all connected clients using the binary protocol.
     * Removes clients that have disconnected.
     *
     * @param type Message type (0x01 for video, 0x02 for audio)
     * @param payload Message payload bytes
     */
    private suspend fun broadcast(type: Int, payload: ByteArray) {
        if (clients.isEmpty()) return

        val deadClients = mutableListOf<ClientConnection>()

        for (client in clients) {
            try {
                withContext(Dispatchers.IO) {
                    Protocol.writeMessage(client.outputStream, type, payload)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Client ${client.id} disconnected: ${e.message}")
                deadClients.add(client)
            }
        }

        // Clean up disconnected clients
        if (deadClients.isNotEmpty()) {
            for (client in deadClients) {
                removeClient(client)
            }
        }
    }

    /**
     * Remove a client and close its socket.
     */
    private fun removeClient(client: ClientConnection) {
        try {
            client.socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing client socket", e)
        }
        clients.remove(client)
        _clientCount.value = clients.size
        Log.i(TAG, "Client removed: ${client.id}. Total: ${clients.size}")
    }

    /**
     * Stop the server and disconnect all clients.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        _isRunning.value = false

        // Close all client connections
        for (client in clients) {
            try {
                client.socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing client socket during shutdown", e)
            }
        }
        clients.clear()
        _clientCount.value = 0

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        acceptJob?.cancel()
        acceptJob = null

        Log.i(TAG, "Stream server stopped")
    }

    /**
     * Get the list of connected client IDs (for UI display).
     */
    fun getConnectedClientIds(): List<String> = clients.map { it.id }

    /**
     * Get the current number of connected clients.
     */
    fun getClientCount(): Int = clients.size
}
