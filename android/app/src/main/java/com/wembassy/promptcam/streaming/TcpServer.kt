package com.wembassy.promptcam.streaming

import android.util.Log
import com.wembassy.promptcam.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP server that streams H.264 video and AAC audio frames to connected desktop clients.
 * 
 * This server:
 * - Listens on a configurable port (default 8554)
 * - Accepts multiple client connections simultaneously
 * - Sends framed A/V data using the PromptCam binary protocol:
 *     Video: [0x01] [4-byte big-endian length] [H.264 NAL data]
 *     Audio: [0x02] [4-byte big-endian length] [AAC data]
 * - Handles client disconnections gracefully
 * - Emits connection state via a StateFlow for UI updates
 * 
 * The server collects encoded frames from VideoEncoder and AudioEncoder
 * SharedFlows and broadcasts them to all connected clients.
 * 
 * @param port TCP port to listen on (default 8554)
 * @param scope Coroutine scope for launching collection coroutines
 */
class TcpServer(
    private val port: Int = Protocol.DEFAULT_VIDEO_PORT,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TcpServer"
        private const val SOCKET_BACKLOG = 5
        private const val SOCKET_TIMEOUT_MS = 0  // Infinite (blocking accept)
    }

    // Server state
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var isRunning = false

    // Connected clients — thread-safe for concurrent access
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    // Client connection state for UI
    data class ClientConnection(
        val socket: Socket,
        val outputStream: OutputStream,
        val id: String
    )

    // Reactive state
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private val _isRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _isRunning

    /**
     * Start the TCP server and begin accepting connections.
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
            Log.i(TAG, "TCP server listening on port $port")

            // Launch accept loop in IO dispatcher
            acceptJob = scope.launch(Dispatchers.IO) {
                acceptConnections()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start TCP server on port $port", e)
            isRunning = false
            _isRunning.value = false
            throw e
        }
    }

    /**
     * Accept loop — runs in a coroutine, accepting client connections.
     * Each client gets its own handler.
     */
    private suspend fun acceptConnections() {
        while (isRunning && scope.isActive) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                Log.i(TAG, "Client connected: ${clientSocket.inetAddress}")

                val client = ClientConnection(
                    socket = clientSocket,
                    outputStream = clientSocket.getOutputStream(),
                    id = "${clientSocket.inetAddress}:${clientSocket.port}"
                )
                clients.add(client)
                _clientCount.value = clients.size

                // Keep socket alive for long connections
                try {
                    clientSocket.keepAlive = true
                    clientSocket.tcpNoDelay = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set socket options", e)
                }

            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Error accepting client connection", e)
                }
            }
        }
    }

    /**
     * Send a video frame (H.264 NAL unit) to all connected clients.
     * Uses the framing protocol: [0x01] [4-byte length] [NAL data]
     * 
     * @param nalData Raw H.264 NAL unit data from the encoder
     */
    suspend fun sendVideoFrame(nalData: ByteArray) {
        if (clients.isEmpty()) return
        val frame = Protocol.frame(Protocol.TYPE_VIDEO, nalData)
        broadcast(frame)
    }

    /**
     * Send an audio frame (AAC data) to all connected clients.
     * Uses the framing protocol: [0x02] [4-byte length] [AAC data]
     * 
     * @param aacData Raw AAC frame data from the encoder
     */
    suspend fun sendAudioFrame(aacData: ByteArray) {
        if (clients.isEmpty()) return
        val frame = Protocol.frame(Protocol.TYPE_AUDIO, aacData)
        broadcast(frame)
    }

    /**
     * Broadcast raw framed data to all connected clients.
     * Removes clients that have disconnected.
     */
    private suspend fun broadcast(data: ByteArray) {
        if (clients.isEmpty()) return

        val deadClients = mutableListOf<ClientConnection>()

        for (client in clients) {
            try {
                withContext(Dispatchers.IO) {
                    client.outputStream.write(data)
                    client.outputStream.flush()
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

        // Cancel accept job
        acceptJob?.cancel()
        acceptJob = null

        Log.i(TAG, "TCP server stopped")
    }

    /**
     * Get the list of connected client IDs (for UI display).
     */
    fun getConnectedClientIds(): List<String> = clients.map { it.id }
}
