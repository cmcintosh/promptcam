package com.wembassy.promptcam.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * H.264 video encoder using Android's MediaCodec API.
 *
 * This encoder:
 * - Creates an H.264/AVC encoder with the specified resolution and bitrate
 * - Accepts input via a Surface (from Camera2 preview/record output)
 * - Produces H.264 NAL units as output, including SPS/PPS config frames
 * - Emits encoded NAL units via a callback interface and SharedFlow
 *
 * The encoder runs asynchronously using MediaCodec callbacks.
 * NAL units are emitted as ByteArray payloads (raw H.264 data, no framing header).
 * The TCP server adds the binary protocol framing.
 *
 * @param width Video width in pixels (e.g. 1280)
 * @param height Video height in pixels (e.g. 720)
 * @param bitrate Target bitrate in bits per second (e.g. 4_000_000)
 * @param frameRate Target frame rate in FPS (e.g. 30)
 * @param iFrameInterval Key frame interval in seconds (e.g. 2)
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val frameRate: Int = 30,
    private val iFrameInterval: Int = 2
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = "video/avc"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRunning = false

    // Callback interface for encoded frames
    interface EncoderCallback {
        fun onEncodedFrame(data: ByteArray, isKeyFrame: Boolean, isConfigFrame: Boolean)
    }

    var callback: EncoderCallback? = null

    // Flow of encoded NAL units for coroutine-based consumption
    private val _encodedFrames = MutableSharedFlow<EncodedFrame>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val encodedFrames: SharedFlow<EncodedFrame> = _encodedFrames.asSharedFlow()

    /**
     * Encoded frame data with metadata.
     */
    data class EncodedFrame(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val isConfigFrame: Boolean,
        val timestamp: Long
    )

    /**
     * The input surface for this encoder.
     * Camera2 output should be directed to this surface.
     * Available after prepare() is called.
     */
    fun getInputSurface(): Surface {
        if (inputSurface == null) {
            throw IllegalStateException("Encoder not prepared. Call prepare() first.")
        }
        return inputSurface!!
    }

    /**
     * Prepare the encoder. Creates the MediaCodec and configures it.
     * Must be called before start().
     */
    fun prepare() {
        if (codec != null) {
            Log.w(TAG, "Encoder already prepared")
            return
        }

        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MIME_TYPE)
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // Set AVC profile for compatibility
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            val profileLevel = MediaCodecInfo.CodecProfileLevel()
            profileLevel.profile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            profileLevel.level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        try {
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec!!.createInputSurface()
            Log.i(TAG, "Encoder prepared: ${width}x${height} @ ${frameRate}fps, ${bitrate}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare encoder", e)
            codec?.release()
            codec = null
            throw e
        }
    }

    /**
     * Start the encoder with asynchronous callback mode.
     * NAL units will be emitted via encodedFrames flow and callback interface.
     *
     * @param handler Handler for encoder callbacks (use camera background handler)
     */
    fun start(handler: Handler) {
        if (codec == null) {
            throw IllegalStateException("Encoder not prepared. Call prepare() first.")
        }
        if (isRunning) {
            Log.w(TAG, "Encoder already running")
            return
        }

        codec!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                // Not used for Surface input — the codec feeds from the Surface automatically
            }

            override fun onOutputBufferAvailable(
                c: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                if (!isRunning) return

                val outputBuffer = c.getOutputBuffer(index) ?: run {
                    c.releaseOutputBuffer(index, false)
                    return
                }

                if (info.size > 0) {
                    // Extract NAL data from the output buffer
                    val data = ByteArray(info.size)
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    outputBuffer.get(data)

                    // Determine frame type from NAL header
                    val isConfigFrame = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    // Emit via callback interface
                    callback?.onEncodedFrame(data.copyOf(), isKeyFrame, isConfigFrame)

                    // Emit via SharedFlow
                    val frame = EncodedFrame(
                        data = data.copyOf(),
                        isKeyFrame = isKeyFrame,
                        isConfigFrame = isConfigFrame,
                        timestamp = info.presentationTimeUs
                    )
                    _encodedFrames.tryEmit(frame)

                    if (isConfigFrame) {
                        Log.d(TAG, "Config frame (SPS/PPS): ${data.size} bytes")
                    } else if (isKeyFrame) {
                        Log.d(TAG, "Key frame: ${data.size} bytes")
                    }
                }

                c.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format changed: $format")
                // SPS/PPS are available in the format's "csd-0" and "csd-1" buffers
                // These will also be delivered via onOutputBufferAvailable with BUFFER_FLAG_CODEC_CONFIG
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error", e)
                isRunning = false
            }
        })

        codec!!.start()
        isRunning = true
        Log.i(TAG, "Encoder started")
    }

    /**
     * Request a key frame (I-frame) from the encoder.
     * Useful when a new client connects mid-stream so they can decode immediately.
     */
    fun requestKeyFrame() {
        if (!isRunning || codec == null) return

        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec!!.setParameters(params)
            Log.i(TAG, "Key frame requested")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request key frame", e)
        }
    }

    /**
     * Stop the encoder and release all resources.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping encoder", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing encoder", e)
        }

        inputSurface = null
        codec = null
        Log.i(TAG, "Encoder stopped and released")
    }

    /**
     * Signal end of stream to the encoder.
     * Use this before stopping for a clean shutdown.
     */
    fun signalEndOfStream() {
        if (!isRunning || codec == null) return
        try {
            codec!!.signalEndOfInputStream()
            Log.i(TAG, "End of stream signaled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to signal end of stream", e)
        }
    }

    /**
     * Check if the encoder is currently running.
     */
    fun isRunning(): Boolean = isRunning
}
