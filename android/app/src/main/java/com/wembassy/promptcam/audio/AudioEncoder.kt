package com.wembassy.promptcam.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Audio capture and AAC encoder using AudioRecord + MediaCodec.
 *
 * This class:
 * - Captures audio from the microphone using AudioRecord (16kHz, mono, 16-bit PCM)
 * - Feeds PCM data into a MediaCodec AAC encoder
 * - Emits encoded AAC frames with ADTS headers via a SharedFlow for network transmission
 * - Runs capture and encoding on a background coroutine
 *
 * The encoded AAC frames include ADTS headers so they can be streamed directly
 * without additional container formatting. The desktop client can decode them
 * with any AAC decoder that supports ADTS.
 *
 * @param sampleRate Audio sample rate in Hz (default 16000)
 * @param bitrate AAC encoder bitrate in bits per second (default 64000)
 */
class AudioEncoder(
    private val sampleRate: Int = 16_000,
    private val bitrate: Int = 64_000
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val CHANNEL_COUNT = 1 // Mono
        private const val TIMEOUT_US = 10_000L
    }

    // AudioRecord configuration
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // State
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var captureJob: Job? = null
    private var isRunning = false

    // Buffer sizes
    private var minBufferSize: Int = 0
    private var bufferSize: Int = 0

    // Callback interface for encoded frames
    interface EncoderCallback {
        fun onEncodedFrame(data: ByteArray)
    }

    var callback: EncoderCallback? = null

    // Flow of encoded AAC frames (with ADTS headers)
    private val _encodedFrames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val encodedFrames: SharedFlow<ByteArray> = _encodedFrames.asSharedFlow()

    /**
     * Prepare the AudioRecord and MediaCodec encoder.
     * Must be called before start().
     *
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     * @throws RuntimeException if AudioRecord or MediaCodec initialization fails
     */
    fun prepare() {
        if (isRunning) {
            Log.w(TAG, "AudioEncoder already prepared/running")
            return
        }

        // Calculate buffer sizes
        minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            // Fallback to a reasonable default
            minBufferSize = 3200 // ~100ms at 16kHz, 16-bit, mono
        }
        bufferSize = maxOf(minBufferSize * 2, 8192)

        // Initialize AudioRecord
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
            Log.i(TAG, "Audio encoder prepared: ${sampleRate}Hz, mono, ${bitrate}bps AAC")
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

        isRunning = true
        codec!!.start()
        audioRecord?.startRecording()
        Log.i(TAG, "Audio capture and encoding started")

        // Launch audio capture coroutine
        captureJob = scope.launch(Dispatchers.IO) {
            captureLoop()
        }

        // Launch encoder output drain coroutine
        scope.launch(Dispatchers.IO) {
            drainEncoderLoop()
        }
    }

    /**
     * Audio capture loop — reads PCM data from AudioRecord and feeds to MediaCodec.
     */
    private suspend fun captureLoop() {
        val buffer = ByteArray(bufferSize)

        while (isRunning && codec != null) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read <= 0) continue

            try {
                val inputBufferIndex = codec!!.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec!!.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(buffer, 0, read)
                        codec!!.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            read,
                            System.currentTimeMillis() * 1000, // presentation time in microseconds
                            0
                        )
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Error feeding audio encoder", e)
                }
            }
        }
    }

    /**
     * Encoder output drain loop — extracts encoded AAC frames from MediaCodec.
     */
    private suspend fun drainEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning && codec != null) {
            try {
                val outputBufferIndex = codec!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet, continue
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Output format changed — typically happens once at start
                        Log.i(TAG, "Audio output format changed: ${codec!!.outputFormat}")
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec!!.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Extract encoded AAC data
                            val encodedData = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(encodedData)

                            // Add ADTS header for streaming compatibility
                            val adtsData = addAdtsHeader(encodedData, bufferInfo.size)

                            // Emit via callback
                            callback?.onEncodedFrame(adtsData)

                            // Emit via SharedFlow
                            _encodedFrames.tryEmit(adtsData)
                        }

                        codec!!.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Error draining audio encoder", e)
                }
            }
        }
    }

    /**
     * Add ADTS (Audio Data Transport Stream) header to AAC raw data.
     * This allows the stream to be decoded by standard AAC decoders without
     * a container format.
     *
     * ADTS header is 7 bytes (no CRC) or 9 bytes (with CRC).
     * We use the 7-byte variant (no CRC).
     *
     * @param aacData Raw AAC frame data from the encoder
     * @param aacDataLength Length of the AAC data
     * @return AAC data with ADTS header prepended
     */
    private fun addAdtsHeader(aacData: ByteArray, aacDataLength: Int): ByteArray {
        val adtsLength = aacDataLength + 7 // 7-byte ADTS header
        val adtsData = ByteArray(adtsLength)

        // ADTS header fields:
        // - Syncword: 12 bits (0xFFF)
        // - ID: 1 bit (0 = MPEG-4, 1 = MPEG-2)
        // - Layer: 2 bits (always 00)
        // - Protection absent: 1 bit (1 = no CRC)
        // - Profile: 2 bits (0 = AAC-LC)
        // - Sampling frequency index: 4 bits
        // - Private bit: 1 bit (0)
        // - Channel configuration: 3 bits
        // - Originality/copy: 1 bit (0)
        // - Home: 1 bit (0)
        // - Copyright identification bit: 1 bit (0)
        // - Copyright identification start: 1 bit (0)
        // - Frame length: 13 bits (total frame length including header)
        // - Buffer fullness: 11 bits (0x7FF = variable bit rate)
        // - Number of AAC frames: 2 bits (0 = 1 frame)

        val sampleRateIndex = getSampleRateIndex(sampleRate)

        // Byte 0-1: Syncword + ID + Layer + Protection absent + Profile + Sampling freq index (high)
        adtsData[0] = 0xFF.toByte() // Syncword high 8 bits
        adtsData[1] = (0xF9.toByte()) // Syncword low 4 bits (0xF) + ID(0) + Layer(00) + Protection absent(1)

        // Byte 2: Sampling freq index (low) + Private + Channel config + Original + Home
        adtsData[2] = (((sampleRateIndex and 0x0F) shl 2) or
                ((CHANNEL_COUNT shr 2) and 0x01) or 0x00).toByte()

        // Byte 3: Channel config (low) + Copyright bits + Frame length (high 2 bits)
        adtsData[3] = (((CHANNEL_COUNT and 0x03) shl 6) or
                ((adtsLength shr 11) and 0x03)).toByte()

        // Byte 4: Frame length (middle 8 bits)
        adtsData[4] = ((adtsLength shr 3) and 0xFF).toByte()

        // Byte 5: Frame length (low 3 bits) + Buffer fullness (high 5 bits)
        adtsData[5] = (((adtsLength and 0x07) shl 5) or 0x1F).toByte()

        // Byte 6: Buffer fullness (low 6 bits) + Number of AAC frames (2 bits, 0 = 1 frame)
        adtsData[6] = 0xFC.toByte()

        // Copy AAC data after the ADTS header
        System.arraycopy(aacData, 0, adtsData, 7, aacDataLength)

        return adtsData
    }

    /**
     * Get the ADTS sampling frequency index for a given sample rate.
     */
    private fun getSampleRateIndex(rate: Int): Int {
        return when (rate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 8 // Default to 16kHz index
        }
    }

    /**
     * Stop audio capture and encoding, release all resources.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio encoder", e)
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio codec", e)
        }

        audioRecord = null
        codec = null
        Log.i(TAG, "Audio encoder stopped and released")
    }

    /**
     * Check if the encoder is currently running.
     */
    fun isRunning(): Boolean = isRunning
}
