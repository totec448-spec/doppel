package de.totec.doppel.engine

import kotlin.random.Random

/**
 * Online-SESSION model — the single source of truth for "does she have her phone
 * in her hand right now?".
 *
 * Two clocks, at two scales:
 *
 *  - **The window.** Literally "is the dot on". It is opened and closed by
 *    [PresenceCoordinator], which owns the visible presence, so the behaviour and
 *    the indicator can no longer disagree. They used to: the dot went out ten to
 *    thirty seconds after the last bubble while the engine went on treating the
 *    chat as open for two full minutes, which is exactly the "it went offline and
 *    then read my message instantly" report. While the window is open the chat in
 *    front of her is answered without any pickup wait at all; once it closes,
 *    [offlineForMs] starts counting and drives the ladder in [HumanTimingPolicy].
 *
 *  - **The session.** The half-hour behavioural stretch around all that activity
 *    ([SESSION_LINGER_MS]), extended by every window. The phone is nearby and
 *    gets picked up on its own; the proactive layer reads the same state, so the
 *    two cannot drift apart.
 *
 * Every online window resets both, whatever opened it — an answer, a proactive
 * message, or a self-initiated look at the phone with nothing to say.
 */
class OnlineSessionClock(
    private val clock: EngineClock = SystemEngineClock,
    private val random: Random = Random.Default,
) {
    /**
     * When she last actually SENT something, anywhere. Seeded with "now" so a
     * fresh install is not treated as away-forever, which would delay the very
     * first reply by the full cold band.
     */
    @Volatile
    private var lastOnlineAtMs: Long = clock.wallTimeMillis()

    @Volatile
    private var sessionEndsAtMs: Long = 0L

    /** Whether the dot is on right now. */
    @Volatile
    private var online: Boolean = false

    /**
     * The chat she is looking at, or `null` for "online, no chat in particular" —
     * a self-initiated session, where every chat is equally in front of her.
     */
    @Volatile
    private var onlineChatJid: String? = null

    /** When the dot last went out. Drives the whole pickup ladder. */
    @Volatile
    private var offlineSinceMs: Long = clock.wallTimeMillis()

    /** When she last sent anything, used to tell a live exchange from a resumed one. */
    @Volatile
    private var lastReplyAtMs: Long = 0L

    /** When the current run of back-and-forth started; `0` when there is no run. */
    @Volatile
    private var burstStartedAtMs: Long = 0L

    /** How long *this* run is allowed to last, drawn once when it starts. */
    @Volatile
    private var burstStaminaMs: Long = 0L

    /** While in the future, a spent burst is being sat out. */
    @Volatile
    private var coolingUntilMs: Long = 0L

    /**
     * Seed both clocks from the persisted last send at startup, so a restart
     * neither believes the bot has been away forever nor resurrects a session
     * that ended hours ago.
     */
    @Synchronized
    fun seed(lastSendAtMs: Long?) {
        val seedMs = lastSendAtMs?.takeIf { it > 0L } ?: return
        lastOnlineAtMs = seedMs
        // The dot would have lingered a little past that send and then gone out.
        offlineSinceMs = seedMs + PresenceCoordinator.IDLE_OFF_MIN_MS
        if (clock.wallTimeMillis() - seedMs < SESSION_LINGER_MS) {
            sessionEndsAtMs = seedMs + SESSION_LINGER_MS
        }
    }

    /**
     * She came online (opened the chat to read/decide) but has not necessarily
     * sent anything yet: opens or extends the session, but does not advance the
     * away clock — nothing observable was sent.
     */
    @Synchronized
    fun noteCameOnline(nowMs: Long = clock.wallTimeMillis()) {
        sessionEndsAtMs = nowMs + SESSION_LINGER_MS
    }

    /** She just sent something. Advances the away clock and extends the session. */
    @Synchronized
    fun noteOnline(nowMs: Long = clock.wallTimeMillis()) {
        lastOnlineAtMs = nowMs
        noteBurst(nowMs)
        noteCameOnline(nowMs)
    }

    /**
     * Track how long the current run of back-and-forth has been going, and end it when it has gone
     * on longer than anyone keeps that up.
     *
     * Measured in elapsed time rather than messages, which is what makes it immune to how the
     * answer was split: a reply that goes out as four bubbles two seconds apart is one turn of a
     * conversation, and counting bubbles would have retired the burst four times faster in exactly
     * the chats that are most alive.
     */
    private fun noteBurst(nowMs: Long) {
        if (nowMs < coolingUntilMs) {
            // Still sitting one out. Replies keep going, they are simply slow; none of them may
            // restart the fast exchange before the break is over, or the break would never happen
            // in the one situation it is for — a correspondent who always answers immediately.
            lastReplyAtMs = nowMs
            return
        }
        if (nowMs - lastReplyAtMs > RAPID_GAP_MS) {
            burstStartedAtMs = nowMs
            burstStaminaMs = between(BURST_STAMINA_MIN_MS, BURST_STAMINA_MAX_MS)
        } else if (nowMs - burstStartedAtMs >= burstStaminaMs) {
            coolingUntilMs = nowMs + between(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS)
        }
        lastReplyAtMs = nowMs
    }

    /**
     * How live the conversation is, and whether a spent burst is being sat out.
     *
     * [HumanTimingPolicy.pickupPlan] turns this into where the delay curve peaks. Reading it is
     * free of side effects on purpose: it is called once per scheduled message and must not itself
     * count as activity.
     */
    fun exchangeTempo(nowMs: Long = clock.wallTimeMillis()): ExchangeTempo {
        if (nowMs < coolingUntilMs) return ExchangeTempo(heat = 0.0, cooling = true)
        if (lastReplyAtMs <= 0L) return ExchangeTempo()
        val since = (nowMs - lastReplyAtMs).coerceAtLeast(0L)
        return ExchangeTempo(heat = (1.0 - since.toDouble() / RAPID_GAP_MS).coerceIn(0.0, 1.0))
    }

    private fun between(
        minMs: Long,
        maxMs: Long,
    ): Long = if (maxMs <= minMs) minMs else random.nextLong(minMs, maxMs + 1)

    /**
     * The dot just went on. [chatJid] is the chat she opened, or `null` when she
     * merely picked the phone up and is not in any conversation in particular.
     */
    @Synchronized
    fun noteWentOnline(
        chatJid: String?,
        nowMs: Long = clock.wallTimeMillis(),
    ) {
        online = true
        onlineChatJid = chatJid
        noteCameOnline(nowMs)
    }

    /** The dot went out. From here on the pickup ladder counts up from [nowMs]. */
    @Synchronized
    fun noteWentOffline(nowMs: Long = clock.wallTimeMillis()) {
        if (!online) return
        online = false
        onlineChatJid = null
        offlineSinceMs = nowMs
    }

    /**
     * True while the message would land in front of her eyes: the dot is on and
     * either she is in this very chat or she is not in a specific one.
     */
    fun isChatOpen(
        chatJid: String,
        @Suppress("UNUSED_PARAMETER") nowMs: Long = clock.wallTimeMillis(),
    ): Boolean = online && onlineChatJid.let { it == null || it == chatJid }

    /** How long the dot has been out; `0` while it is on. */
    fun offlineForMs(nowMs: Long = clock.wallTimeMillis()): Long =
        if (online) 0L else (nowMs - offlineSinceMs).coerceAtLeast(0L)

    /** When the dot last went out — the anchor the self-session gap is measured from. */
    fun offlineSince(): Long = offlineSinceMs

    /** She put the phone down for good — a pause, a block, a shutdown. */
    @Synchronized
    fun closeChat(nowMs: Long = clock.wallTimeMillis()) {
        noteWentOffline(nowMs)
    }

    fun lastOnlineAt(): Long = lastOnlineAtMs

    /** True while she is in an online session. */
    fun inSession(nowMs: Long = clock.wallTimeMillis()): Boolean = nowMs < sessionEndsAtMs

    companion object {
        /** How long after her last online activity she still counts as in a session. */
        const val SESSION_LINGER_MS = 30 * 60_000L

        /**
         * The longest gap two replies may have and still belong to the same run of back-and-forth.
         *
         * Doubles as the window heat decays over: a reply three minutes old contributes nothing,
         * one that went out seconds ago contributes everything.
         */
        const val RAPID_GAP_MS = 3 * 60_000L

        /**
         * How long a run of back-and-forth may last before the phone goes down.
         *
         * Drawn per burst rather than fixed, because the cooldown is the one behaviour here that an
         * observer could otherwise time: an account that goes quiet after exactly twenty-five
         * minutes of fast replies, every time, has published its own state machine.
         */
        const val BURST_STAMINA_MIN_MS = 15 * 60_000L
        const val BURST_STAMINA_MAX_MS = 35 * 60_000L

        /** How long the phone stays down afterwards. */
        const val COOLDOWN_MIN_MS = 45 * 60_000L
        const val COOLDOWN_MAX_MS = 120 * 60_000L
    }
}
