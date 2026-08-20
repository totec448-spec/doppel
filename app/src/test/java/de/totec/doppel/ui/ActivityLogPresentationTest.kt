package de.totec.doppel.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogPresentationTest {
    @Test
    fun `activity rows are revealed in bounded pages`() {
        assertEquals(0, ActivityLogPresentation.visibleCount(requested = 30, total = 0))
        assertEquals(12, ActivityLogPresentation.visibleCount(requested = 30, total = 12))
        assertEquals(30, ActivityLogPresentation.visibleCount(requested = 30, total = 100))
        assertEquals(60, ActivityLogPresentation.nextRequestedCount(displayed = 30))
    }

    @Test
    fun `requesting past the loaded window is how older pages get fetched`() {
        // 90 rows loaded, all shown: the next tap asks for more than exists so the controller
        // widens its window, while rendering stays clamped until those rows actually arrive.
        val requested = ActivityLogPresentation.nextRequestedCount(displayed = 90)
        assertEquals(120, requested)
        assertEquals(90, ActivityLogPresentation.visibleCount(requested = requested, total = 90))
        assertEquals(120, ActivityLogPresentation.visibleCount(requested = requested, total = 340))
    }

    @Test
    fun `operator labels read as prose while protocol ids remain separate`() {
        assertEquals("WhatsApp link", ActivityLogPresentation.categoryLabel("bridge"))
        assertEquals("Memory", ActivityLogPresentation.categoryLabel("memory"))
        assertEquals("WARNING", ActivityLogPresentation.levelLabel("warn"))
    }

    @Test
    fun `each executed command gets a glyph that matches what it did`() {
        assertEquals("📥", ActivityLogPresentation.glyph("inbound", "received"))
        assertEquals("📤", ActivityLogPresentation.glyph("outbound", "send_text"))
        assertEquals("💗", ActivityLogPresentation.glyph("outbound", "send_reaction"))
        assertEquals("🖼️", ActivityLogPresentation.glyph("outbound", "send_media"))
        assertEquals("🎙️", ActivityLogPresentation.glyph("voice_reply", "send_voice_note"))
        assertEquals("🎧", ActivityLogPresentation.glyph("bridge", "mark_played"))
        assertEquals("👁️", ActivityLogPresentation.glyph("bridge", "mark_read"))
        assertEquals("🤖", ActivityLogPresentation.glyph("ai_stage", "model_dispatch"))
        assertEquals("⚡", ActivityLogPresentation.glyph("media_analysis", "cache_hit"))
        assertEquals("🔧", ActivityLogPresentation.glyph("tool_trace", "search_current_chat"))
        assertEquals("🚫", ActivityLogPresentation.glyph("admin", "autoblock"))
    }

    /**
     * The action vocabulary is open — stage names, tool names and outbox frame kinds all land here
     * — so the ambiguous overlaps are what actually decide whether the log reads correctly.
     */
    @Test
    fun `overlapping action names resolve to the more important meaning`() {
        // Failure beats the thing that failed.
        assertEquals("❌", ActivityLogPresentation.glyph("memory", "automatic_refresh_failed"))
        assertEquals("❌", ActivityLogPresentation.glyph("image_reply", "image_send_failed"))
        // A blocked verification is a verification, not a contact block.
        assertEquals("🔍", ActivityLogPresentation.glyph("ai_stage", "verification_blocked"))
        // Substrings that swallow other keywords: "ready" contains "read", "autostart" contains
        // "start", and a TTS stage stays a voice stage whatever it is called.
        assertEquals("✅", ActivityLogPresentation.glyph("bridge", "ready"))
        assertEquals("🎙️", ActivityLogPresentation.glyph("voice_trace", "tts_ogg_ready"))
        assertEquals("⚙️", ActivityLogPresentation.glyph("app", "autostart_changed"))
        assertEquals("💤", ActivityLogPresentation.glyph("ai_stage", "no_reply"))
        assertEquals("🛡️", ActivityLogPresentation.glyph("transport_safety", "safety_blocked"))
    }

    @Test
    fun `unknown actions still get a glyph from the category or the level`() {
        assertEquals("🔗", ActivityLogPresentation.glyph("bridge", "socket_frame"))
        assertEquals("⚠️", ActivityLogPresentation.glyph("whatever", "socket_frame", level = "warn"))
        assertEquals("❌", ActivityLogPresentation.glyph("whatever", "socket_frame", level = "error"))
    }

    @Test
    fun `severity outranks the subsystem colour`() {
        assertEquals(ActivityAccent.CYAN, ActivityLogPresentation.accent("bridge", "info"))
        assertEquals(ActivityAccent.RED, ActivityLogPresentation.accent("bridge", "error"))
        assertEquals(ActivityAccent.YELLOW, ActivityLogPresentation.accent("bridge", "warn"))
        assertEquals(ActivityAccent.GRAY, ActivityLogPresentation.accent("unknown_area", "info"))
    }
}
