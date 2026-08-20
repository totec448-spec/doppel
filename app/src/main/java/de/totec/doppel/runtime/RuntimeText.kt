package de.totec.doppel.runtime

/**
 * The handful of runtime labels the notification and the status panel share.
 *
 * They live here rather than inline so the notification title, the Overview headline and the
 * activity log cannot drift apart while all three describe the same phase.
 */
internal object RuntimeText {
    const val TITLE_RUNNING = "Doppel is running"
    const val TITLE_ATTENTION = "Doppel needs attention"
    const val CONNECTION_DEGRADED = "Connection is degraded"
    const val ENGINE_UNAVAILABLE = "The bot engine is unavailable"
}
