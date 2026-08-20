package de.totec.doppel.media

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Converts OpenRouter's 24 kHz signed little-endian PCM into the 48 kHz mono
 * PCM expected by the Android Opus encoder. The optional "phone" treatment is
 * deliberately one streaming-style pass and allocates only the final sample
 * array; no native ffmpeg process or second AI call is involved.
 */
object VoiceDsp {
    const val INPUT_SAMPLE_RATE = 24_000
    const val OUTPUT_SAMPLE_RATE = 48_000
    const val TAIL_SECONDS = 1

    /** WhatsApp's voice-note bubble draws exactly this many bars, each on a 0..100 scale. */
    const val WAVEFORM_BARS = 64

    /** Below 1.0 this lifts quiet passages; 1.0 would be plain linear RMS. */
    private const val WAVEFORM_COMPRESSION = 0.65

    fun prepare(
        pcm24Khz: ByteArray,
        quality: Int,
        randomSeed: Long,
    ): ByteArray {
        require(quality in 1..10) { "quality must be 1..10" }
        require(pcm24Khz.size % 2 == 0) { "PCM must contain complete 16-bit samples" }
        val inputSamples = pcm24Khz.size / 2
        val outputSamples = inputSamples * 2 + OUTPUT_SAMPLE_RATE * TAIL_SECONDS
        val result = ByteArray(outputSamples * 2)
        val strength = (quality - 1) / 9.0
        val random = Random(randomSeed)

        val highPassHz = 80.0 + 260.0 * strength
        val lowPassHz = 11_000.0 - 7_500.0 * strength
        val highAlpha = highPassAlpha(highPassHz, OUTPUT_SAMPLE_RATE)
        val lowAlpha = lowPassAlpha(lowPassHz, OUTPUT_SAMPLE_RATE)
        val crushStep = if (strength == 0.0) 1 else (1 shl (strength * 8.0).roundToInt())
        val noisePeak = (strength * 550.0).roundToInt()

        var previousInput = 0.0
        var highPassed = 0.0
        var lowPassed = 0.0
        for (outputIndex in 0 until outputSamples) {
            val sourcePosition = outputIndex / 2
            val raw =
                if (sourcePosition < inputSamples) {
                    val current = readSample(pcm24Khz, sourcePosition)
                    if (outputIndex % 2 == 0 || sourcePosition + 1 >= inputSamples) {
                        current
                    } else {
                        (current + readSample(pcm24Khz, sourcePosition + 1)) / 2
                    }
                } else {
                    0
                }

            val input = raw.toDouble()
            highPassed = highAlpha * (highPassed + input - previousInput)
            previousInput = input
            lowPassed += lowAlpha * (highPassed - lowPassed)
            var shaped = lowPassed.roundToInt()
            if (crushStep > 1) shaped = (shaped / crushStep) * crushStep
            if (noisePeak > 0 && sourcePosition < inputSamples) {
                shaped += random.nextInt(-noisePeak, noisePeak + 1)
            }
            writeSample(result, outputIndex, shaped.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        return result
    }

    fun durationSeconds(preparedPcm: ByteArray): Double =
        preparedPcm.size.toDouble() / 2.0 / OUTPUT_SAMPLE_RATE

    /**
     * The 64-bar amplitude envelope WhatsApp draws under a voice note.
     *
     * It has to be measured, not invented: one constant pattern under every voice note is a
     * machine-readable fingerprint, because no two real recordings look alike. The PCM is already
     * in hand here, so this is one linear pass over it — decoding the finished OGG again would
     * cost another codec dependency for the same numbers.
     *
     * Bars are RMS per window, normalized against the loudest window and mildly compressed, so a
     * quiet passage still draws a visible bar instead of collapsing to the baseline the way raw
     * linear amplitude does.
     */
    fun waveform(preparedPcm: ByteArray): ByteArray {
        val totalSamples = preparedPcm.size / 2
        val bars = ByteArray(WAVEFORM_BARS)
        if (totalSamples <= 0) return bars
        val energies = DoubleArray(WAVEFORM_BARS)
        var peak = 0.0
        for (bar in 0 until WAVEFORM_BARS) {
            val start = (totalSamples.toLong() * bar / WAVEFORM_BARS).toInt()
            val end = (totalSamples.toLong() * (bar + 1) / WAVEFORM_BARS).toInt()
            if (end <= start) continue
            var sum = 0.0
            for (index in start until end) {
                val sample = readSample(preparedPcm, index).toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / (end - start))
            energies[bar] = rms
            if (rms > peak) peak = rms
        }
        if (peak <= 0.0) return bars
        for (bar in 0 until WAVEFORM_BARS) {
            val scaled = (energies[bar] / peak).pow(WAVEFORM_COMPRESSION) * 100.0
            bars[bar] = scaled.roundToInt().coerceIn(0, 100).toByte()
        }
        return bars
    }

    private fun readSample(bytes: ByteArray, index: Int): Int {
        val offset = index * 2
        return ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun writeSample(bytes: ByteArray, index: Int, sample: Int) {
        val offset = index * 2
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
    }

    private fun lowPassAlpha(cutoffHz: Double, sampleRate: Int): Double =
        1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)

    private fun highPassAlpha(cutoffHz: Double, sampleRate: Int): Double {
        val delta = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        return rc / (rc + delta)
    }
}
