package com.wembassy.promptcam.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.ByteBuffer

/**
 * H.264 video encoder using Android's MediaCodec API.
 * 
 * This encoder:
 * - Creates an H.264/AVC encoder with the specified resolution and bitrate
 * - Accepts input via a Surface (from Camera2 preview output)
 * - Produces H.264 NAL units as output
 * - Emits encoded NAL units via a SharedFlow for network transmission
 * 
 * The encoder runs asynchronously using MediaCodec callbacks, so no
 * manual polling is needed. NAL units are emitted as ByteArray payloads
 * (without the framing header — framing is added by the TCP server).
 * 
 * @param width Video width in pixels
 * @param height Video height in pixels  
 * @param bitrate Target bitrate in bits per second
 * @param frameRate Target frame rate in FPS
 * @param iFrameInterval Key frame interval in seconds
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
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRunning = false

    // Flow of encoded NAL units (raw H.264 data, no framing header)
    private val _encodedFrames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val encodedFrames: SharedFlow<ByteArray> = _encodedFrames

    /**
     * The input surface for this encoder.
     * Camera2 preview output should be directed to this surface.
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
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        try {
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec!!.createInputSurface()
            Log.i(TAG, "Encoder prepared: ${width}x${height} @ ${bitrate}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare encoder", e)
            codec?.release()
            codec = null
            throw e
        }
    }

    /**
     * Start the encoder with asynchronous callback mode.
     * NAL units will be emitted via encodedFrames flow.
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
                // Not used for Surface input — the codec feeds from the Surface
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

                // Extract NAL units from the output buffer
                val data = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)
                outputBuffer.get(data)

                // Check for config buffer (SPS/PPS) or encoded frame
                if (info.size > 0) {
                    // Emit the encoded NAL data. The TCP server will add framing.
                    // We emit both config (SPS/PPS) and regular frames.
                    val nalData = data.copyOf()
                    
                    // Use tryEmit to avoid blocking the codec callback
                    _encodedFrames.tryEmit(nalData)
                }

                c.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format changed: $format")
                // The format change indicates SPS/PPS are available.
                // The config data will come through onOutputBufferAvailable as well.
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
     * Useful when a new client connects mid-stream.
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
     * Stop the encoder and release resources.
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
     * Use this before stopping for clean shutdown.
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
}
