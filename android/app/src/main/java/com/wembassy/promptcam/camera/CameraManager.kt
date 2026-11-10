package com.wembassy.promptcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Camera2 wrapper that manages camera lifecycle, preview surface, and frame capture.
 * 
 * This class handles:
 * - Camera device open/close with proper permission checks
 * - Preview output to a provided Surface (from TextureView or MediaCodec input surface)
 * - Frame capture via ImageReader for H.264 encoding
 * - Background handler thread for camera operations
 * - Thread-safe camera access with a semaphore lock
 * 
 * @param context Application or activity context
 * @param lensFacing CameraCharacteristics.LENS_FACING_FRONT or LENS_FACING_BACK
 * @param targetSize Desired capture resolution (width, height)
 */
class CameraManager(
    private val context: Context,
    private val lensFacing: Int,
    private val targetSize: Size
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val CAMERA_LOCK_TIMEOUT_MS = 2500L
        private const val MAX_IMAGES = 10
    }

    // Camera state
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Background thread for camera callbacks
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Camera characteristics
    private var cameraId: String? = null
    private var sensorOrientation: Int = 0

    // State flow for camera readiness
    private val _cameraState = MutableStateFlow(CameraState.IDLE)
    val cameraState: StateFlow<CameraState> = _cameraState

    // Callback for captured frames (YUV_420_888)
    var onFrameAvailable: ((Image) -> Unit)? = null

    enum class CameraState { IDLE, OPENING, OPENED, CLOSED, ERROR }

    /**
     * Start the background handler thread.
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
                    // Verify this camera supports our target resolution
                    val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    ) ?: continue

                    // Check if our target size is supported (or find closest)
                    val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                    if (sizes != null && sizes.isNotEmpty()) {
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
     * Open the camera. Creates an ImageReader for frame capture.
     * 
     * @param previewSurface Surface for preview display (from TextureView or encoder)
     */
    fun openCamera(previewSurface: Surface) {
        if (cameraDevice != null) {
            Log.w(TAG, "Camera already open")
            return
        }

        startBackgroundThread()
        this.previewSurface = previewSurface

        cameraId = findCameraId()
        if (cameraId == null) {
            Log.e(TAG, "No suitable camera found for lensFacing=$lensFacing")
            _cameraState.value = CameraState.ERROR
            return
        }

        // Create ImageReader for frame capture (YUV_420_888 for encoder)
        imageReader = ImageReader.newInstance(
            targetSize.width,
            targetSize.height,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        )
        imageReader?.setOnImageAvailableListener(
            { reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                onFrameAvailable?.invoke(image)
                image.close()
            },
            backgroundHandler
        )

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
                    Log.i(TAG, "Camera opened: ${cameraId}")
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
     * Create a camera capture session with preview surface and ImageReader surface.
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        val reader = imageReader ?: return

        val surfaces = listOf(surface, reader.surface)

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
     * Start the repeating capture request for continuous preview + frame capture.
     */
    private fun startRepeatingRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val reader = imageReader ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD
            )
            requestBuilder.addTarget(surface)
            requestBuilder.addTarget(reader.surface)

            // Set auto-focus
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            // Set auto-exposure
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            )

            session.setRepeatingRequest(
                requestBuilder.build(),
                null,  // No capture callback needed for repeating preview
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
            imageReader?.close()
            imageReader = null
            previewSurface = null
            _cameraState.value = CameraState.CLOSED
            Log.i(TAG, "Camera closed")
        } finally {
            cameraOpenCloseLock.release()
        }

        stopBackgroundThread()
    }

    /**
     * Get the sensor orientation for this camera.
     * Useful for correct preview rotation.
     */
    fun getSensorOrientation(): Int = sensorOrientation
}
