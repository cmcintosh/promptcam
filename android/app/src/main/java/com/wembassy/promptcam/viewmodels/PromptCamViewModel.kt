package com.wembassy.promptcam.viewmodels

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wembassy.promptcam.AppSettings
import com.wembassy.promptcam.CameraLens
import com.wembassy.promptcam.SettingsManager
import com.wembassy.promptcam.VideoResolution
import com.wembassy.promptcam.audio.AudioEncoder
import com.wembassy.promptcam.camera.CameraManager
import com.wembassy.promptcam.camera.VideoEncoder
import com.wembassy.promptcam.network.ControlServer
import com.wembassy.promptcam.network.DiscoveryService
import com.wembassy.promptcam.network.StreamServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ViewModel that coordinates camera, encoders, and network servers.
 *
 * Manages streaming state (IDLE, STREAMING, ERROR) and holds references to:
 * - CameraManager (Camera2 API wrapper)
 * - VideoEncoder (MediaCodec H.264 encoder)
 * - AudioEncoder (AudioRecord + MediaCodec AAC encoder)
 * - StreamServer (TCP video/audio stream server on port 8554)
 * - ControlServer (TCP control server on port 8555)
 *
 * Connects:
 * - CameraManager encoder surface → VideoEncoder input
 * - VideoEncoder callback → StreamServer.sendVideoFrame()
 * - AudioEncoder callback → StreamServer.sendAudioFrame()
 * - ControlServer → teleprompter state (text, scroll position)
 *
 * Exposes StateFlows for UI observation:
 * - streamingState: IDLE / STREAMING / ERROR
 * - teleprompterText: current teleprompter text from desktop
 * - scrollPosition: current scroll position (0.0-1.0)
 * - clientCount: number of connected stream clients
 * - settings: current app settings
 */
class PromptCamViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PromptCamViewModel"
    }

    /**
     * Streaming state enum.
     */
    enum class StreamingState {
        IDLE, STREAMING, ERROR
    }

    private val settingsManager = SettingsManager(application)

    // Settings
    private val _settings = MutableStateFlow(
        AppSettings(
            cameraLens = CameraLens.BACK,
            resolution = VideoResolution.P720,
            bitrate = 2_500_000,
            videoPort = 8554,
            controlPort = 8555
        )
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Streaming state
    private val _streamingState = MutableStateFlow(StreamingState.IDLE)
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    // Teleprompter state (from ControlServer)
    private val _teleprompterText = MutableStateFlow("")
    val teleprompterText: StateFlow<String> = _teleprompterText.asStateFlow()

    private val _scrollPosition = MutableStateFlow(0.0f)
    val scrollPosition: StateFlow<Float> = _scrollPosition.asStateFlow()

    private val _fontSize = MutableStateFlow(24)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    // Connection state
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    // Device IP address (for display so user knows what to connect to)
    private val _deviceIp = MutableStateFlow("")
    val deviceIp: StateFlow<String> = _deviceIp.asStateFlow()

    // Component references
    private var cameraManager: CameraManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var streamServer: StreamServer? = null
    private var controlServer: ControlServer? = null

    // Preview surface from the UI
    private var previewSurface: Surface? = null

    init {
        // Load settings from DataStore
        viewModelScope.launch {
            settingsManager.settingsFlow.collect { loadedSettings ->
                _settings.value = loadedSettings
            }
        }

        // Get device IP address for display
        _deviceIp.value = getDeviceIpAddress()

        // Start control server immediately (always listening for teleprompter commands)
        startControlServer()
    }

    /**
     * Get the device's Wi-Fi IP address for display.
     */
    private fun getDeviceIpAddress(): String {
        return try {
            val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
            if (ip == "0.0.0.0") "— Check Wi-Fi —" else ip
        } catch (e: Exception) {
            Log.w(TAG, "Could not get Wi-Fi IP", e)
            "— Check Wi-Fi —"
        }
    }

    /**
     * Called when the preview SurfaceTexture is available from the UI.
     * Stores the surface and opens the camera for preview.
     */
    fun onPreviewSurfaceAvailable(surface: Surface) {
        Log.i(TAG, "Preview surface available")
        previewSurface = surface

        // Open camera for preview (even if not streaming yet)
        val currentSettings = _settings.value
        if (cameraManager == null) {
            cameraManager = CameraManager(
                context = getApplication(),
                lensFacing = currentSettings.cameraLens.facing,
                targetWidth = currentSettings.resolution.width,
                targetHeight = currentSettings.resolution.height
            )
            cameraManager?.openCamera(surface)
        }
    }

    /**
     * Called when the preview SurfaceTexture is destroyed.
     */
    fun onPreviewSurfaceDestroyed() {
        Log.i(TAG, "Preview surface destroyed")
        if (_streamingState.value != StreamingState.STREAMING) {
            // Only close camera if not streaming (during streaming, encoder surface is also used)
            cameraManager?.closeCamera()
            cameraManager = null
        }
        previewSurface = null
    }

    /**
     * Start streaming: start video encoder, audio encoder, and stream server.
     * Camera must already be opened for preview.
     */
    fun startStreaming() {
        if (_streamingState.value == StreamingState.STREAMING) {
            Log.w(TAG, "Already streaming")
            return
        }

        Log.i(TAG, "Starting streaming...")
        val currentSettings = _settings.value

        try {
            // Start stream server
            streamServer = StreamServer(
                port = currentSettings.videoPort,
                scope = viewModelScope
            ).apply {
                start()
                // Observe client count
                viewModelScope.launch {
                    clientCount.collect { count ->
                        _clientCount.value = count
                    }
                }
            }

            // Create and prepare video encoder
            videoEncoder = VideoEncoder(
                width = currentSettings.resolution.width,
                height = currentSettings.resolution.height,
                bitrate = currentSettings.bitrate,
                frameRate = 30
            ).apply {
                prepare()

                // Set callback to send frames to stream server
                callback = object : VideoEncoder.EncoderCallback {
                    override fun onEncodedFrame(data: ByteArray, isKeyFrame: Boolean, isConfigFrame: Boolean) {
                        // Send via coroutine to avoid blocking encoder callback
                        viewModelScope.launch {
                            streamServer?.sendVideoFrame(data)
                        }
                    }
                }

                // Start encoder with camera's background handler
                cameraManager?.let { cm ->
                    // Need to restart camera with encoder surface
                    val encoderSurface = getInputSurface()

                    // Close current camera session and reopen with encoder surface
                    cm.closeCamera()
                    cameraManager = null

                    val newCameraManager = CameraManager(
                        context = getApplication(),
                        lensFacing = currentSettings.cameraLens.facing,
                        targetWidth = currentSettings.resolution.width,
                        targetHeight = currentSettings.resolution.height
                    )
                    cameraManager = newCameraManager

                    // Open camera with preview surface and encoder surface
                    previewSurface?.let { preview ->
                        newCameraManager.openCamera(preview, encoderSurface)
                    }

                    // Start the encoder once camera handler is available
                    newCameraManager.startBackgroundThread()
                    // We need a handler from the camera manager's background thread
                    // Use the main looper handler as fallback, camera callbacks handle their own thread
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    start(handler)
                }
            }

            // Create and start audio encoder
            audioEncoder = AudioEncoder(
                sampleRate = 16_000,
                bitrate = 64_000
            ).apply {
                prepare()

                callback = object : AudioEncoder.EncoderCallback {
                    override fun onEncodedFrame(data: ByteArray) {
                        viewModelScope.launch {
                            streamServer?.sendAudioFrame(data)
                        }
                    }
                }

                start(viewModelScope)
            }

            // Start discovery service
            DiscoveryService.start(
                context = getApplication(),
                videoPort = currentSettings.videoPort,
                controlPort = currentSettings.controlPort
            )

            _streamingState.value = StreamingState.STREAMING
            Log.i(TAG, "Streaming started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            _streamingState.value = StreamingState.ERROR
            // Clean up partial state
            stopStreamingInternal()
        }
    }

    /**
     * Stop streaming: stop encoders and stream server.
     * Camera preview continues if surface is still available.
     */
    fun stopStreaming() {
        if (_streamingState.value != StreamingState.STREAMING) {
            return
        }

        Log.i(TAG, "Stopping streaming...")
        stopStreamingInternal()
        _streamingState.value = StreamingState.IDLE

        // Reopen camera for preview only (without encoder surface)
        previewSurface?.let { surface ->
            val currentSettings = _settings.value
            cameraManager = CameraManager(
                context = getApplication(),
                lensFacing = currentSettings.cameraLens.facing,
                targetWidth = currentSettings.resolution.width,
                targetHeight = currentSettings.resolution.height
            )
            cameraManager?.openCamera(surface)
        }
    }

    /**
     * Internal cleanup for streaming components.
     */
    private fun stopStreamingInternal() {
        // Stop audio encoder
        audioEncoder?.stop()
        audioEncoder = null

        // Stop video encoder
        videoEncoder?.stop()
        videoEncoder = null

        // Close camera (will reopen for preview if needed)
        cameraManager?.closeCamera()
        cameraManager = null

        // Stop stream server
        streamServer?.stop()
        streamServer = null
        _clientCount.value = 0

        // Stop discovery service
        DiscoveryService.stop(getApplication())
    }

    /**
     * Start the control server for receiving teleprompter commands.
     */
    private fun startControlServer() {
        val currentSettings = _settings.value
        controlServer = ControlServer(
            port = currentSettings.controlPort,
            scope = viewModelScope
        ).apply {
            start()

            // Set callback to update teleprompter state
            callback = object : ControlServer.ControlCallback {
                override fun onControlCommand(command: String) {
                    Log.i(TAG, "Control command: $command")
                    when (command) {
                        "start" -> startStreaming()
                        "stop" -> stopStreaming()
                        "switch_camera" -> switchCamera()
                    }
                }

                override fun onTeleprompterText(data: com.wembassy.promptcam.protocol.TeleprompterData) {
                    _teleprompterText.value = data.text
                    _fontSize.value = data.fontSize
                }

                override fun onScrollPosition(position: Float) {
                    _scrollPosition.value = position
                }
            }
        }
    }

    /**
     * Switch between front and back camera.
     */
    fun switchCamera() {
        Log.i(TAG, "Switching camera")
        val currentSettings = _settings.value
        val newLens = if (currentSettings.cameraLens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT

        // Update settings
        viewModelScope.launch {
            settingsManager.updateCameraLens(newLens)
        }

        // Update local state
        val newSettings = currentSettings.copy(cameraLens = newLens)
        _settings.value = newSettings

        // Switch camera if currently active
        val wasStreaming = _streamingState.value == StreamingState.STREAMING
        if (wasStreaming) {
            stopStreamingInternal()
        }

        previewSurface?.let { surface ->
            cameraManager?.closeCamera()
            cameraManager = null

            val newCameraManager = CameraManager(
                context = getApplication(),
                lensFacing = newLens.facing,
                targetWidth = newSettings.resolution.width,
                targetHeight = newSettings.resolution.height
            )
            cameraManager = newCameraManager

            if (wasStreaming) {
                // Restart streaming with new camera
                // For now, just reopen preview; full streaming restart would re-init encoder
                newCameraManager.openCamera(surface)
                startStreaming()
            } else {
                newCameraManager.openCamera(surface)
            }
        }
    }

    /**
     * Update app settings.
     */
    fun updateSettings(newSettings: AppSettings) {
        Log.i(TAG, "Updating settings: $newSettings")
        viewModelScope.launch {
            settingsManager.updateAll(newSettings)
        }
        _settings.value = newSettings

        // If streaming, need to restart with new settings
        if (_streamingState.value == StreamingState.STREAMING) {
            stopStreaming()
            startStreaming()
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel cleared — cleaning up")
        stopStreamingInternal()
        controlServer?.stop()
        controlServer = null
        DiscoveryService.stop(getApplication())
    }
}
