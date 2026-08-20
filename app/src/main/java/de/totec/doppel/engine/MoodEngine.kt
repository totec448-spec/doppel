package de.totec.doppel.engine

import java.time.Instant
import java.time.ZoneId
import kotlin.math.absoluteValue

data class Mood(
    val key: String,
    val promptHint: String,
    val leaveOnReadBias: Double,
)

/**
 * One deterministic shared mood in six-hour blocks. No timer is needed: the
 * value is recomputed only when a turn is assembled.
 */
object MoodEngine {
    private const val BLOCK_HOURS = 6
    private val moods =
        listOf(
            Mood("neutral", "calm and even", 0.03),
            Mood("warm", "open, warm and attentive", 0.0),
            Mood("focused", "brief, focused and clear", 0.05),
            Mood("playful", "relaxed and slightly playful", 0.01),
            Mood("tired", "a bit tired and withdrawn", 0.12),
            Mood("distant", "cool and rather monosyllabic", 0.20),
        )

    fun at(timestampMs: Long, zoneId: ZoneId): Mood {
        val local = Instant.ofEpochMilli(timestampMs).atZone(zoneId)
        val block = local.toLocalDate().toEpochDay() * (24 / BLOCK_HOURS) + local.hour / BLOCK_HOURS
        val index = mix64(block).rem(moods.size.toLong()).toInt().absoluteValue
        return moods[index]
    }

    private fun mix64(value: Long): Long {
        var x = value
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }
}
