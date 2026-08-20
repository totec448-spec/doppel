package de.totec.doppel.ui

import java.util.TimeZone

/**
 * One log row, fully formatted.
 *
 * Rows used to format their own timestamp and label during composition, so every scroll frame paid
 * for a fresh `DateFormat` (a locale lookup plus a pattern compile) per visible row. Formatting
 * moves out of the draw path and happens once per entry.
 */
internal data class ActivityRowModel(
    val id: Long,
    /** `HH:mm:ss` — the precise instant, shown once the row is opened. */
    val time: String,
    /**
     * `HH:mm` — what the collapsed gutter shows. Eight monospace digits never fit the 46dp column
     * and were being cut to `09:34:`, a clock with a dangling colon; the seconds are one tap away
     * on the expanded row, which is where one instant actually gets compared with another.
     */
    val clock: String,
    /** Single glyph for the executed command, so the log can be scanned by shape alone. */
    val glyph: String,
    /** `category/action` — the machine name of the command, shown on the collapsed line. */
    val technical: String,
    /** Plain-language operator label for [technical], shown only when the row is expanded. */
    val category: String,
    val level: String,
    val message: String,
    val details: String?,
    val error: Boolean,
    val warning: Boolean,
    val accent: ActivityAccent,
)

/**
 * Terminal colour slot for a row. Kept as an enum rather than a Compose `Color` so the whole
 * formatting layer stays a plain unit-testable object with no UI dependency.
 */
internal enum class ActivityAccent {
    GRAY,
    CYAN,
    BLUE,
    GREEN,
    MAGENTA,
    YELLOW,
    RED,
}

/**
 * Small, deterministic policy for the operator log.
 *
 * The database remains the complete source of truth. Compose receives the
 * bounded latest snapshot from the controller and materializes it in explicit
 * pages, keeping initial composition and tab changes independent of log size.
 */
internal object ActivityLogPresentation {
    const val PAGE_SIZE = 30

    /** An expanded row must not turn one huge payload into a dropped frame. */
    const val MAX_DETAIL_CHARACTERS = 2_000

    fun visibleCount(
        requested: Int,
        total: Int,
    ): Int = requested.coerceAtLeast(PAGE_SIZE).coerceAtMost(total.coerceAtLeast(0))

    /**
     * The request size after a "load more" tap. It is deliberately not clamped to what is loaded:
     * asking for more rows than the controller currently holds is how the screen signals that the
     * next database page is needed, and [visibleCount] keeps rendering clamped until it arrives.
     */
    fun nextRequestedCount(displayed: Int): Int = displayed.coerceAtLeast(0) + PAGE_SIZE

    fun categoryLabel(category: String): String = categoryStyle(category)?.label ?: "System"

    /**
     * The glyph for one executed command.
     *
     * The action vocabulary is open — stage names, tool names and outbox frame kinds all end up
     * here — so this matches on meaning instead of enumerating every value: an unknown
     * `..._failed` still reads as a failure, an unknown `send_...` still reads as an outbound send.
     * Failure and abort are checked first on purpose; when a command went wrong, that is the thing
     * worth seeing while scrolling, and the exact action name is right next to the glyph anyway.
     */
    fun glyph(
        category: String,
        action: String,
        level: String = "info",
    ): String {
        val normalizedAction = action.trim().lowercase()
        ACTION_GLYPHS.forEach { (keyword, glyph) ->
            if (normalizedAction.contains(keyword)) return glyph
        }
        categoryStyle(category)?.glyph?.let { return it }
        return when (level.trim().lowercase()) {
            "error" -> GLYPH_FAILURE
            "warn", "warning" -> GLYPH_WARNING
            else -> GLYPH_UNKNOWN
        }
    }

    /** Colour slot for a row: severity first, then the subsystem the command belongs to. */
    fun accent(
        category: String,
        level: String,
    ): ActivityAccent =
        when (level.trim().lowercase()) {
            "error" -> ActivityAccent.RED
            "warn", "warning" -> ActivityAccent.YELLOW
            else -> categoryStyle(category)?.accent ?: ActivityAccent.GRAY
        }

    /**
     * Formats one page of rows. Timestamps are resolved once per whole second — a log page is
     * almost always a handful of distinct seconds — and the visible detail is bounded so one
     * oversized payload cannot stall a frame when its row is expanded.
     *
     * The clock is a fixed 24-hour `HH:mm:ss` rather than a localized time: a terminal log is read
     * as aligned columns, and a locale that appends `AM`/`PM` would ragged every line.
     */
    fun rows(entries: List<UiActivityEntry>): List<ActivityRowModel> {
        val zone = TimeZone.getDefault()
        val bySecond = HashMap<Long, String>()
        return entries.map { entry ->
            val second = entry.timestampMs / 1_000L
            val time = bySecond.getOrPut(second) { formatClock(entry.timestampMs, zone) }
            val level = entry.level.trim().lowercase()
            ActivityRowModel(
                id = entry.id,
                time = time,
                clock = time.substring(0, 5),
                glyph = glyph(entry.category, entry.action, entry.level),
                technical = "${entry.category}/${entry.action}",
                category = categoryLabel(entry.category),
                level = levelLabel(entry.level),
                message = entry.message,
                details = entry.details?.take(MAX_DETAIL_CHARACTERS),
                error = level == "error",
                warning = level == "warn" || level == "warning",
                accent = accent(entry.category, entry.level),
            )
        }
    }

    fun levelLabel(level: String): String =
        when (level.trim().lowercase()) {
            "error" -> "ERROR"
            "warn", "warning" -> "WARNING"
            "debug" -> "DEBUG"
            else -> "INFO"
        }

    /** `HH:mm:ss` in the device time zone, DST included, without going through `DateFormat`. */
    private fun formatClock(
        timestampMs: Long,
        zone: TimeZone,
    ): String {
        val localSeconds = Math.floorDiv(timestampMs + zone.getOffset(timestampMs), 1_000L)
        val secondOfDay = Math.floorMod(localSeconds, 86_400L).toInt()
        val builder = StringBuilder(8)
        appendTwoDigits(builder, secondOfDay / 3_600)
        builder.append(':')
        appendTwoDigits(builder, (secondOfDay / 60) % 60)
        builder.append(':')
        appendTwoDigits(builder, secondOfDay % 60)
        return builder.toString()
    }

    private fun appendTwoDigits(
        builder: StringBuilder,
        value: Int,
    ) {
        if (value < 10) builder.append('0')
        builder.append(value)
    }

    private const val GLYPH_FAILURE = "❌"
    private const val GLYPH_WARNING = "⚠️"
    private const val GLYPH_UNKNOWN = "▫️"

    /**
     * Ordered on purpose: the first keyword contained in the action wins. `automatic_refresh_failed`
     * must read as a failure and not as a refresh, and `verification_blocked` must read as a
     * verification and not as a contact block.
     */
    private val ACTION_GLYPHS: List<Pair<String, String>> =
        listOf(
            "fail" to GLYPH_FAILURE,
            "error" to GLYPH_FAILURE,
            "dead_letter" to "☠️",
            "unsupported" to GLYPH_FAILURE,
            "unavailable" to GLYPH_FAILURE,
            "denied" to GLYPH_FAILURE,
            "discontinuity" to "⚠️",
            "interrupt" to "⛔",
            "abort" to "⛔",
            "cancel" to "⛔",
            "verif" to "🔍",
            // Before "block": `safety_blocked` is a safety gate, not a contact block.
            "safety" to "🛡️",
            "restricted" to "🛡️",
            "timelock" to "🛡️",
            "block" to "🚫",
            "ban" to "🚫",
            "receiv" to "📥",
            "inbound" to "📥",
            "react" to "💗",
            "image" to "🖼️",
            "media" to "🖼️",
            "photo" to "🖼️",
            "voice" to "🎙️",
            "tts" to "🎙️",
            "speech" to "🎙️",
            "audio" to "🎙️",
            "listen" to "🎧",
            "played" to "🎧",
            "transcri" to "🎧",
            // Before "read", which it contains.
            "ready" to "✅",
            "read" to "👁️",
            "receipt" to "👁️",
            "typing" to "⌨️",
            "presence" to "⌨️",
            "online" to "🟢",
            "offline" to "⚫",
            "sleep" to "⚫",
            "memor" to "🧠",
            "tool" to "🔧",
            "model" to "🤖",
            "prompt" to "🤖",
            "dispatch" to "🤖",
            "cache" to "⚡",
            "retry" to "🔁",
            "attempt" to "🔁",
            "reconnect" to "🔁",
            "defer" to "⏳",
            "queue" to "⏳",
            "schedul" to "⏳",
            "wait" to "⏳",
            "pickup" to "⏳",
            "skip" to "💤",
            "silent" to "💤",
            "no_reply" to "💤",
            "empty" to "💤",
            "proactive" to "📡",
            "wander" to "📡",
            "refresh" to "🔄",
            "reanchor" to "🔄",
            "sync" to "🔄",
            "sent" to "📤",
            "send" to "📤",
            "outbound" to "📤",
            "deliver" to "📤",
            "upload" to "📤",
            "reply" to "💬",
            "quote" to "💬",
            // Both before "start" / "connect", which they contain.
            "autostart" to "⚙️",
            "disconnect" to "🔌",
            "start" to "▶️",
            "materializ" to "▶️",
            "prepar" to "▶️",
            "succe" to "✅",
            "complet" to "✅",
            "finish" to "✅",
            "confirm" to "✅",
            "connect" to "✅",
            "onboarding" to "⚙️",
            "polic" to "⚙️",
            "config" to "⚙️",
            "setting" to "⚙️",
        )

    private data class CategoryStyle(
        val label: String,
        val glyph: String,
        val accent: ActivityAccent,
    )

    private fun categoryStyle(category: String): CategoryStyle? =
        CATEGORY_STYLES[category.trim().lowercase()]

    private fun style(
        label: String,
        glyph: String,
        accent: ActivityAccent,
    ) = CategoryStyle(label, glyph, accent)

    private val CATEGORY_STYLES: Map<String, CategoryStyle> =
        mapOf(
            "admin" to style("Admin", "⚙️", ActivityAccent.YELLOW),
            "ai_network" to style("AI network", "🌐", ActivityAccent.BLUE),
            "ai_stage" to style("AI pipeline", "🤖", ActivityAccent.BLUE),
            "ai_tools" to style("Tools", "🔧", ActivityAccent.YELLOW),
            "app" to style("App", "📱", ActivityAccent.GRAY),
            "bridge" to style("WhatsApp link", "🔗", ActivityAccent.CYAN),
            "bridge_outbox" to style("WhatsApp outbox", "📤", ActivityAccent.GREEN),
            "engine_deferred" to style("Queue", "⏳", ActivityAccent.GRAY),
            "engine_online" to style("Presence", "🟢", ActivityAccent.GREEN),
            "engine_turn" to style("Reply pipeline", "🔄", ActivityAccent.BLUE),
            "image_reply" to style("Image reply", "🖼️", ActivityAccent.MAGENTA),
            "inbound" to style("Incoming", "📥", ActivityAccent.CYAN),
            "media_analysis" to style("Media analysis", "🔎", ActivityAccent.MAGENTA),
            "memory" to style("Memory", "🧠", ActivityAccent.BLUE),
            "outbound" to style("Outgoing", "📤", ActivityAccent.GREEN),
            "tool_trace" to style("Tools", "🔧", ActivityAccent.YELLOW),
            "transport_safety" to style("Transport safety", "🛡️", ActivityAccent.YELLOW),
            "tts" to style("Speech output", "🎙️", ActivityAccent.MAGENTA),
            "voice_reply" to style("Voice reply", "🎙️", ActivityAccent.MAGENTA),
            "voice_trace" to style("Voice pipeline", "🎙️", ActivityAccent.MAGENTA),
        )
}
