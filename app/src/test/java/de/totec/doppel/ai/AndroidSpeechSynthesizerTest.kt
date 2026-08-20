package de.totec.doppel.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpeechSynthesizerTest {
    @Test
    fun `stereo 48 kHz PCM WAV becomes mono 24 kHz PCM`() {
        val source =
            shortArrayOf(
                1_000, 3_000,
                2_000, 4_000,
                3_000, 5_000,
                4_000, 6_000,
            )
        val wav = wavPcm16(sampleRate = 48_000, channels = 2, samples = source)

        val result = WavToPcm24Khz.decode(wav)

        assertEquals(24_000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        assertEquals(4, result.bytes.size)
        val samples = ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2_000, samples.short.toInt())
        assertEquals(4_000, samples.short.toInt())
    }

    @Test
    fun `malformed WAV is rejected`() {
        val failure = runCatching { WavToPcm24Khz.decode(byteArrayOf(1, 2, 3)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun wavPcm16(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
    ): ByteArray {
        val dataSize = samples.size * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
            samples.forEach(::putShort)
        }.array()
    }
}
