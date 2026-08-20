package de.totec.doppel.media

import android.media.MediaCodec
import android.media.AudioFormat
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EncodedVoiceNote(
    val file: File,
    val durationSeconds: Int,
    /** The 64-bar, 0..100 envelope WhatsApp draws under the bubble, measured on this note's PCM. */
    val waveform: ByteArray,
) {
    // ByteArray identity would make two notes with the same envelope compare unequal, which is
    // never what a caller comparing these means.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is EncodedVoiceNote &&
                    file == other.file &&
                    durationSeconds == other.durationSeconds &&
                    waveform.contentEquals(other.waveform)
            )

    override fun hashCode(): Int =
        (file.hashCode() * 31 + durationSeconds) * 31 + waveform.contentHashCode()
}

class VoiceEncodingUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Native PCM -> OGG/Opus path. MediaMuxer's OGG container starts at API 29;
 * API 28 devices receive a clear capability error so the runtime can fall back
 * to text instead of shipping malformed "voice" bytes.
 */
class VoiceNoteEncoder {
    private val encodeMutex = Mutex()

    suspend fun encode(
        rawPcm24Khz: ByteArray,
        destination: File,
        quality: Int,
        randomSeed: Long,
    ): EncodedVoiceNote =
        encodeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw VoiceEncodingUnavailableException(
                        "Native OGG/Opus encoding requires Android 10 or newer",
                    )
                }
                val prepared = VoiceDsp.prepare(rawPcm24Khz, quality, randomSeed)
                destination.parentFile?.mkdirs()
                if (destination.exists()) destination.delete()
                try {
                    encodePreparedPcm(prepared, destination, quality)
                    val seconds = VoiceDsp.durationSeconds(prepared).toInt().coerceAtLeast(1)
                    // Measured on the prepared PCM, so the bars describe the audio that was
                    // actually encoded rather than the raw synthesizer output.
                    EncodedVoiceNote(destination, seconds, VoiceDsp.waveform(prepared))
                } catch (error: Throwable) {
                    destination.delete()
                    if (error is VoiceEncodingUnavailableException) throw error
                    throw VoiceEncodingUnavailableException(
                        "Device could not encode an OGG/Opus voice note",
                        error,
                    )
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun encodePreparedPcm(
        pcm: ByteArray,
        output: File,
        quality: Int,
    ) {
        val format =
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                VoiceDsp.OUTPUT_SAMPLE_RATE,
                1,
            ).apply {
                val strength = (quality - 1) / 9.0
                setInteger(MediaFormat.KEY_BIT_RATE, (24_000 - 12_000 * strength).toInt())
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_CHUNK_BYTES)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
        val codec =
            try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            } catch (error: Throwable) {
                throw VoiceEncodingUnavailableException("No Opus encoder is available", error)
            }
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var inputOffset = 0
        var inputEnded = false
        var outputEnded = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            val activeMuxer =
                MediaMuxer(
                    output.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                ).also { muxer = it }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw VoiceEncodingUnavailableException("Opus input buffer missing")
                        inputBuffer.clear()
                        val remaining = pcm.size - inputOffset
                        val count =
                            minOf(remaining, inputBuffer.remaining(), INPUT_CHUNK_BYTES) and 1.inv()
                        if (count > 0) {
                            inputBuffer.put(pcm, inputOffset, count)
                            val samplesBefore = inputOffset / 2
                            val presentationUs =
                                samplesBefore * 1_000_000L / VoiceDsp.OUTPUT_SAMPLE_RATE
                            codec.queueInputBuffer(inputIndex, 0, count, presentationUs, 0)
                            inputOffset += count
                        } else {
                            val presentationUs =
                                (pcm.size / 2) * 1_000_000L / VoiceDsp.OUTPUT_SAMPLE_RATE
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Opus output format changed twice" }
                        trackIndex = activeMuxer.addTrack(codec.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            val encoded =
                                codec.getOutputBuffer(outputIndex)
                                    ?: throw VoiceEncodingUnavailableException("Opus output buffer missing")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                check(muxerStarted && trackIndex >= 0) { "Muxer was not started" }
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                activeMuxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            outputEnded =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    companion object {
        private const val INPUT_CHUNK_BYTES = 24_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
