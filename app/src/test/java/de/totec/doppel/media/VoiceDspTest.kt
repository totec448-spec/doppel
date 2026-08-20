package de.totec.doppel.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDspTest {
    @Test
    fun `upsamples and adds one second tail`() {
        val oneSecond = ByteArray(VoiceDsp.INPUT_SAMPLE_RATE * 2)

        val prepared = VoiceDsp.prepare(oneSecond, quality = 1, randomSeed = 1)

        assertEquals(VoiceDsp.OUTPUT_SAMPLE_RATE * 2 * 2, prepared.size)
        assertEquals(2.0, VoiceDsp.durationSeconds(prepared), 0.0001)
    }

    @Test
    fun `same seed is deterministic`() {
        val input = ByteArray(2_000) { (it * 13).toByte() }
        assertArrayEquals(
            VoiceDsp.prepare(input, quality = 8, randomSeed = 42),
            VoiceDsp.prepare(input, quality = 8, randomSeed = 42),
        )
    }

    @Test
    fun `quality treatment clamps samples`() {
        val input = ByteArray(2_000) { 0x7f }
        val prepared = VoiceDsp.prepare(input, quality = 10, randomSeed = 3)
        assertTrue(prepared.isNotEmpty())
        assertTrue(prepared.size > input.size)
    }

    @Test
    fun `waveform stays inside the WhatsApp bar scale`() {
        val pcm = sine(seconds = 3.0, amplitude = 12_000.0)

        val bars = VoiceDsp.waveform(pcm)

        assertEquals(VoiceDsp.WAVEFORM_BARS, bars.size)
        bars.forEachIndexed { index, bar ->
            assertTrue("bar $index = $bar", bar.toInt() in 0..100)
        }
        assertEquals(100, bars.max().toInt())
    }

    /**
     * The point of measuring: the bars have to follow the audio. A note that starts loud and ends
     * silent must not draw the same picture as one that does the opposite.
     */
    @Test
    fun `waveform follows the envelope of the audio`() {
        val loud = sine(seconds = 1.0, amplitude = 20_000.0)
        val quiet = ByteArray(loud.size)
        val bars = VoiceDsp.waveform(loud + quiet)

        assertTrue("first half: ${bars.take(32)}", bars.take(32).all { it > 50 })
        assertTrue("second half: ${bars.drop(32)}", bars.drop(32).all { it.toInt() == 0 })
    }

    @Test
    fun `silence and empty input draw a flat waveform`() {
        assertArrayEquals(ByteArray(VoiceDsp.WAVEFORM_BARS), VoiceDsp.waveform(ByteArray(0)))
        assertArrayEquals(ByteArray(VoiceDsp.WAVEFORM_BARS), VoiceDsp.waveform(ByteArray(8_000)))
    }

    private fun sine(seconds: Double, amplitude: Double): ByteArray {
        val samples = (VoiceDsp.OUTPUT_SAMPLE_RATE * seconds).toInt()
        val pcm = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value =
                (
                    amplitude *
                        kotlin.math.sin(
                            2.0 * Math.PI * 220.0 * index / VoiceDsp.OUTPUT_SAMPLE_RATE,
                        )
                ).toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        return pcm
    }
}
