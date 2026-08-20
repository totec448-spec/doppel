package de.totec.doppel.ai

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object LocalSpeechModels {
    const val ANDROID_SYSTEM = "android/system-tts"
}

/**
 * Low-cost device-local fallback for OpenRouter speech routing failures.
 * The Android engine is started only for a requested voice note and shut down
 * immediately afterwards, so it adds no resident background worker.
 */
class AndroidSpeechSynthesizer(context: Context) {
    private val applicationContext = context.applicationContext
    private val outputDirectory = File(applicationContext.cacheDir, "local-tts")

    suspend fun synthesize(text: String): PcmAudio {
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARACTERS)
        outputDirectory.mkdirs()
        val output = File(outputDirectory, "tts-${UUID.randomUUID()}.wav")
        val engine = createEngine()
        return try {
            configureGermanOfflineVoice(engine)
            synthesizeToFile(engine, text.trim(), output)
            val bytes = withContext(Dispatchers.IO) {
                require(output.isFile && output.length() in 1..MAX_WAV_BYTES) {
                    "Android TTS produced no usable audio"
                }
                output.readBytes()
            }
            WavToPcm24Khz.decode(bytes)
        } finally {
            withContext(Dispatchers.Main.immediate) { engine.shutdown() }
            output.delete()
        }
    }

    private suspend fun createEngine(): TextToSpeech =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var engine: TextToSpeech? = null
                engine =
                    TextToSpeech(applicationContext) { status ->
                        val initialized = engine
                        if (status == TextToSpeech.SUCCESS && initialized != null) {
                            if (continuation.isActive) continuation.resume(initialized)
                        } else if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Android TTS initialization failed"),
                            )
                        }
                    }
                continuation.invokeOnCancellation { engine?.shutdown() }
            }
        }

    private suspend fun configureGermanOfflineVoice(engine: TextToSpeech) =
        withContext(Dispatchers.Main.immediate) {
            val offlineGerman =
                engine.voices
                    ?.asSequence()
                    ?.filter { it.locale.language.equals(Locale.GERMAN.language, true) }
                    ?.filterNot { it.isNetworkConnectionRequired }
                    ?.sortedWith(
                        compareByDescending<android.speech.tts.Voice> { it.quality }
                            .thenBy { it.name },
                    )
                    ?.firstOrNull()
            if (offlineGerman != null) {
                engine.voice = offlineGerman
            } else {
                val result = engine.setLanguage(Locale.GERMANY)
                require(result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    "No German Android TTS voice is installed"
                }
            }
            engine.setSpeechRate(1.03f)
        }

    private suspend fun synthesizeToFile(
        engine: TextToSpeech,
        text: String,
        output: File,
    ) {
        val utteranceId = "local-${UUID.randomUUID()}"
        val completion = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id == utteranceId) completion.complete(Unit)
                    }

                    @Deprecated("Deprecated by Android")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            completion.completeExceptionally(
                                IllegalStateException("Android TTS synthesis failed"),
                            )
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            completion.completeExceptionally(
                                IllegalStateException("Android TTS synthesis failed ($errorCode)"),
                            )
                        }
                    }
                },
            )
            check(
                engine.synthesizeToFile(text, Bundle(), output, utteranceId) ==
                    TextToSpeech.SUCCESS,
            ) { "Android TTS request was rejected" }
        }
        withTimeout(SYNTHESIS_TIMEOUT_MS) { completion.await() }
    }

    private companion object {
        const val MAX_TEXT_CHARACTERS = 15_000
        const val MAX_WAV_BYTES = 48L * 1024L * 1024L
        const val SYNTHESIS_TIMEOUT_MS = 30_000L
    }
}

/** Parses PCM16 WAV output and performs a bounded linear 24 kHz mono resample. */
internal object WavToPcm24Khz {
    fun decode(wav: ByteArray): PcmAudio {
        require(wav.size >= 44 && ascii(wav, 0) == "RIFF" && ascii(wav, 8) == "WAVE") {
            "Android TTS returned an invalid WAV container"
        }
        var position = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var pcm: ByteArray? = null
        while (position + 8 <= wav.size) {
            val id = ascii(wav, position)
            val size = littleInt(wav, position + 4)
            require(size >= 0 && position + 8L + size <= wav.size.toLong()) {
                "Android TTS returned a malformed WAV chunk"
            }
            val payload = position + 8
            when (id) {
                "fmt " -> {
                    require(size >= 16)
                    audioFormat = littleShort(wav, payload)
                    channels = littleShort(wav, payload + 2)
                    sampleRate = littleInt(wav, payload + 4)
                    bitsPerSample = littleShort(wav, payload + 14)
                }
                "data" -> pcm = wav.copyOfRange(payload, payload + size)
            }
            position = payload + size + (size and 1)
        }
        val source = requireNotNull(pcm) { "Android TTS WAV has no audio data" }
        require(audioFormat == 1 && bitsPerSample == 16 && channels in 1..2 && sampleRate > 0) {
            "Android TTS WAV format is not supported"
        }
        val frameBytes = channels * 2
        val inputFrames = source.size / frameBytes
        require(inputFrames > 0 && source.size % frameBytes == 0)
        val mono = ShortArray(inputFrames) { frame ->
            var sum = 0
            repeat(channels) { channel ->
                sum += littleSignedShort(source, frame * frameBytes + channel * 2)
            }
            (sum / channels).toShort()
        }
        val outputFrames =
            ((inputFrames.toLong() * TARGET_SAMPLE_RATE + sampleRate / 2L) / sampleRate)
                .coerceIn(1L, MAX_OUTPUT_FRAMES.toLong())
                .toInt()
        val output = ByteBuffer.allocate(outputFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until outputFrames) {
            val sourcePosition = index.toDouble() * sampleRate / TARGET_SAMPLE_RATE
            val left = sourcePosition.toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = sourcePosition - left
            val sample =
                (mono[left] + (mono[right] - mono[left]) * fraction)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(sample.toShort())
        }
        return PcmAudio(output.array(), sampleRateHz = TARGET_SAMPLE_RATE)
    }

    private fun ascii(value: ByteArray, offset: Int): String =
        value.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)

    private fun littleInt(value: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(value, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun littleShort(value: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(value, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleSignedShort(value: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(value, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    private const val TARGET_SAMPLE_RATE = 24_000
    private const val MAX_OUTPUT_FRAMES = 24_000 * 15 * 60
}
