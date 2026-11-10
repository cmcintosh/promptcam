package com.wembassy.promptcam.camera

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Audio capture and AAC encoder using AudioRecord + MediaCodec.
 * 
 * This class:
 * - Captures audio from the microphone using AudioRecord (PCM 16-bit, 44.1kHz, stereo)
 * - Feeds PCM data into a MediaCodec AAC encoder
 * - Emits encoded AAC frames via a SharedFlow for network transmission
 * - Runs capture and encoding on a dedicated background thread
 * 
 * The encoded AAC frames do NOT include the ADTS header — they are raw
 * AAC payload. The desktop client should handle this appropriately.
 * 
 * @param sampleRate Audio sample rate in Hz (default 44100)
 * @param bitrate AAC encoder bitrate in bits per second (default 128000)
 */
class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val bitrate: Int = 128_000
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val CHANNEL_COUNT = 2  // Stereo
        private const val TIMEOUT_US = 10_000L
    }

    // AudioRecord configuration
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // State
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var captureJob: Job? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var isRunning = false

    // Buffer sizes
    private var minBufferSize: Int = 0
    private var bufferSize: Int = 0

    // Flow of encoded AAC frames (raw AAC payload, no ADTS header)
    private val _encodedFrames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val encodedFrames: SharedFlow<ByteArray> = _encodedFrames

    /**
     * Prepare the AudioRecord and MediaCodec encoder.
     * Must be called before start().
     */
    fun prepare() {
        if (isRunning) {
            Log.w(TAG, "AudioEncoder already prepared/running")
            return
        }

        // Calculate buffer sizes
        minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = maxOf(minBufferSize * 2, 8192)  // At least 8KB, typically much larger

        // Initialize AudioRecord
        // Note: Permission must be granted by caller before this point
        try {
            audioRecord = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord initialization failed")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            throw e
        }

        // Initialize MediaCodec AAC encoder
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MIME_TYPE)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNEL_COUNT)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        try {
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.i(TAG, "Audio encoder prepared: ${sampleRate}Hz, ${bitrate}bps AAC")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare audio encoder", e)
            audioRecord?.release()
            audioRecord = null
            codec?.release()
            codec = null
            throw e
        }
    }

    /**
     * Start audio capture and encoding.
     * Launches a coroutine that reads from AudioRecord and feeds the encoder.
     * 
     * @param scope Coroutine scope for the capture loop
     */
    fun start(scope: CoroutineScope) {
        if (!isRunning && codec == null) {
            throw IllegalStateException("Encoder not prepared. Call prepare() first.")
        }
        if (isRunning) {
            Log.w(TAG, "AudioEncoder already running")
            return
        }

        // Start background thread for MediaCodec callbacks
        captureThread = HandlerThread("AudioEncoderCallback").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        isRunning = true

        // Set up async callback for the encoder
        codec!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                if (!isRunning) return
                // Read PCM audio data and feed to encoder
                val inputBuffer = c.getInputBuffer(index) ?: return
                inputBuffer.clear()

                val readResult = audioRecord?.read(inputBuffer, inputBuffer.capacity()) ?: -1
                if (readResult > 0) {
                    c.queueInputBuffer(
                        index,
                        0,
                        readResult,
                        System.currentTimeMillis() * 1000,  // pts in microseconds
                        0
                    )
                } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION ||
                           readResult == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "AudioRecord read error: $readResult")
                }
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
                    // Extract encoded AAC data
                    val data = ByteArray(info.size)
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    outputBuffer.get(data)

                    _encodedFrames.tryEmit(data)
                }

                c.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Audio output format changed: $format")
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Audio encoder error", e)
                isRunning = false
            }
        }, captureHandler)

        // Start the AudioRecord and MediaCodec
        audioRecord?.startRecording()
        codec!!.start()
        Log.i(TAG, "Audio capture and encoding started")
    }

    /**
     * Stop audio capture and release all resources.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        // Stop AudioRecord
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        // Stop and release MediaCodec
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio encoder", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio encoder", e)
        }

        // Release AudioRecord
        audioRecord?.release()
        audioRecord = null

        // Stop callback thread
        captureThread?.quitSafely()
        try {
            captureThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping audio callback thread", e)
        }
        captureThread = null
        captureHandler = null

        codec = null
        Log.i(TAG, "Audio capture and encoding stopped")
    }
}
