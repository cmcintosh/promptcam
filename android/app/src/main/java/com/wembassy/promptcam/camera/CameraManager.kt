package com.wembassy.promptcam.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Camera2 wrapper that manages camera lifecycle, preview surface, and encoding surface.
 *
 * This class handles:
 * - Camera device open/close with proper permission checks
 * - Preview output to a provided Surface (from SurfaceTexture for UI display)
 * - Encoding output to a MediaCodec input Surface (for H.264 encoding)
 * - Front/back camera switching
 * - Background handler thread for camera operations
 * - Thread-safe camera access with a semaphore lock
 * - Target resolution: 1280x720 @ 30fps
 *
 * @param context Application or activity context
 * @param lensFacing CameraCharacteristics.LENS_FACING_FRONT or LENS_FACING_BACK
 * @param targetWidth Desired capture width in pixels
 * @param targetHeight Desired capture height in pixels
 */
class CameraManager(
    private val context: Context,
    private var lensFacing: Int,
    private val targetWidth: Int = 1280,
    private val targetHeight: Int = 720
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val CAMERA_LOCK_TIMEOUT_MS = 2500L
    }

    val targetSize = Size(targetWidth, targetHeight)

    // Camera state
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null

    // Background thread for camera callbacks
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Camera characteristics
    private var cameraId: String? = null
    private var sensorOrientation: Int = 0

    // State flow for camera readiness
    private val _cameraState = MutableStateFlow(CameraState.IDLE)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    enum class CameraState { IDLE, OPENING, OPENED, CLOSED, ERROR }

    /**
     * Start the background handler thread for camera operations.
     * Must be called before openCamera().
     */
    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    /**
     * Stop the background handler thread.
     * Should be called when camera is no longer needed.
     */
    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    /**
     * Find the best camera ID matching the requested lens facing direction.
     * Also retrieves sensor orientation for correct preview rotation.
     */
    private fun findCameraId(): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    ?: continue
                if (facing == lensFacing) {
                    val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    ) ?: continue

                    // Check if surface output is supported (for MediaCodec encoder)
                    val surfaceSizes = map.getOutputSizes(Surface::class.java)
                    if (surfaceSizes != null && surfaceSizes.isNotEmpty()) {
                        sensorOrientation = characteristics.get(
                            CameraCharacteristics.SENSOR_ORIENTATION
                        ) ?: 0
                        return id
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find camera", e)
        }
        return null
    }

    /**
     * Find the best preview size from available sizes, closest to target.
     */
    private fun findBestSize(sizes: Array<Size>): Size {
        var bestSize = sizes[0]
        var bestDiff = Long.MAX_VALUE
        val targetArea = targetWidth.toLong() * targetHeight.toLong()

        for (size in sizes) {
            // Prefer exact match, then closest area
            if (size.width == targetWidth && size.height == targetHeight) {
                return size
            }
            val area = size.width.toLong() * size.height.toLong()
            val diff = Math.abs(area - targetArea)
            if (diff < bestDiff) {
                bestDiff = diff
                bestSize = size
            }
        }
        return bestSize
    }

    /**
     * Open the camera. Creates a capture session with preview and encoder surfaces.
     *
     * @param previewSurface Surface for preview display (from TextureView/SurfaceTexture for UI)
     * @param encoderSurface Surface for MediaCodec H.264 encoder input (null if no encoding)
     */
    fun openCamera(previewSurface: Surface, encoderSurface: Surface? = null) {
        if (cameraDevice != null) {
            Log.w(TAG, "Camera already open")
            return
        }

        startBackgroundThread()
        this.previewSurface = previewSurface
        this.encoderSurface = encoderSurface

        cameraId = findCameraId()
        if (cameraId == null) {
            Log.e(TAG, "No suitable camera found for lensFacing=$lensFacing")
            _cameraState.value = CameraState.ERROR
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager

        try {
            if (!cameraOpenCloseLock.tryAcquire(CAMERA_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Timeout waiting to lock camera opening")
            }

            _cameraState.value = CameraState.OPENING

            // Permission check is handled by caller (Activity)
            manager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    _cameraState.value = CameraState.OPENED
                    Log.i(TAG, "Camera opened: $cameraId")
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    _cameraState.value = CameraState.CLOSED
                    Log.w(TAG, "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    _cameraState.value = CameraState.ERROR
                    Log.e(TAG, "Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Failed to open camera", e)
            _cameraState.value = CameraState.ERROR
        }
    }

    /**
     * Create a camera capture session with preview surface and optional encoder surface.
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return

        val surfaces = mutableListOf(surface)
        if (encoderSurface != null) {
            surfaces.add(encoderSurface!!)
        }

        try {
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingRequest()
                        Log.i(TAG, "Capture session configured")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        _cameraState.value = CameraState.ERROR
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            _cameraState.value = CameraState.ERROR
        }
    }

    /**
     * Start the repeating capture request for continuous preview + encoding.
     */
    private fun startRepeatingRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD
            )
            requestBuilder.addTarget(surface)

            if (encoderSurface != null) {
                requestBuilder.addTarget(encoderSurface!!)
            }

            // Set auto-focus for video
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            // Set auto-exposure
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

            session.setRepeatingRequest(
                requestBuilder.build(),
                null,
                backgroundHandler
            )
            Log.i(TAG, "Repeating capture request started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    /**
     * Close the camera and release all resources.
     * Safe to call multiple times.
     */
    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while waiting for camera lock", e)
        }

        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            previewSurface = null
            encoderSurface = null
            _cameraState.value = CameraState.CLOSED
            Log.i(TAG, "Camera closed")
        } finally {
            cameraOpenCloseLock.release()
        }

        stopBackgroundThread()
    }

    /**
     * Switch between front and back camera.
     * Closes the current camera and reopens with the new lens facing.
     *
     * @param newLensFacing CameraCharacteristics.LENS_FACING_FRONT or LENS_FACING_BACK
     * @param previewSurface Surface for the new preview
     * @param encoderSurface Surface for the encoder (optional)
     */
    fun switchCamera(
        newLensFacing: Int,
        previewSurface: Surface,
        encoderSurface: Surface? = null
    ) {
        if (newLensFacing == lensFacing && cameraDevice != null) {
            Log.w(TAG, "Already using this camera")
            return
        }

        closeCamera()
        lensFacing = newLensFacing
        openCamera(previewSurface, encoderSurface)
    }

    /**
     * Get the current lens facing direction.
     */
    fun getLensFacing(): Int = lensFacing

    /**
     * Get the sensor orientation for this camera.
     * Useful for correct preview rotation.
     */
    fun getSensorOrientation(): Int = sensorOrientation

    /**
     * Get the camera ID currently in use.
     */
    fun getCameraId(): String? = cameraId
}
