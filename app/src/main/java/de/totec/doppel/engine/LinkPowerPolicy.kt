package de.totec.doppel.engine

import kotlin.math.roundToLong
import kotlin.random.Random

/** How much battery the bot may spend on staying reachable. */
enum class PowerMode {
    DEFAULT,
    LOW,
}

/**
 * What the WhatsApp link itself should be doing — deliberately NOT the same thing
 * as the visible online dot.
 *
 * The dot is a behavioural signal owned by [PresenceCoordinator] and it stays
 * exactly as it was. This is the transport underneath it: whether the websocket is
 * connected and whether the CPU is pinned awake to keep it that way. In
 * [PowerMode.DEFAULT] the two are unrelated, because the link is simply always up
 * and the dot blinks on and off inside it. In [PowerMode.LOW] the link follows the
 * dot at a coarser grain: it comes up for the online sessions the bot would have
 * had anyway, lingers while a conversation is live, and drops in between.
 */
enum class LinkState {
    /** Socket connected, wake lock held. */
    AWAKE,

    /** Socket down between online sessions; the device may suspend. Low mode only. */
    DOZING,

    /** Socket down for the quiet hours. Both modes. */
    SLEEPING,
}

/**
 * The engine's side of the decision, published as it changes.
 *
 * Every field is a wall-clock instant rather than a duration, because the whole
 * point of this feed is to survive a suspended CPU: a duration captured before a
 * three-hour deep sleep is meaningless on the other side of it, an instant is not.
 */
data class LinkActivity(
    /**
     * While this is in the future the link must stay up: the bot has just been
     * talking to someone and is still listening for the reply. Pushed out by every
     * inbound message — see [LinkPowerPolicy.listenWindowMs].
     */
    val listenUntilMs: Long = 0L,
    /**
     * When the next self-initiated online session is due. In low mode this is the
     * alarm the device is woken for; `0` means the engine has not planned one.
     */
    val nextSessionAtMs: Long = 0L,
    /** Exact persisted promise, allowed to open a session before the random schedule. */
    val scheduledFollowUpAtMs: Long = 0L,
    /**
     * A turn is running right now. Nothing may drop the link while this is true —
     * not the listen deadline expiring, and not the quiet hours starting.
     */
    val busy: Boolean = false,
)

/** The state the link should be in, and when the decision must be revisited. */
data class LinkPlan(
    val state: LinkState,
    /**
     * Wall clock instant to re-evaluate at, or `null` for "nothing scheduled; the
     * next change comes from an event". Only [LinkState.AWAKE] ever yields `null`
     * — a dozing or sleeping device has no other way back, so those two always
     * name the instant an alarm has to be armed for.
     */
    val wakeAtMs: Long?,
    /**
     * The end of the low-mode session this plan is inside, or `0` for "not in one".
     *
     * Carried back by the controller and handed to the next [LinkPowerPolicy.plan]
     * for the same reason as the other two drawn instants: it is a random draw, and
     * a draw that is repeated on every re-plan is not a schedule. Without it the
     * session lasted exactly one call — the next poke found nothing holding the link
     * up and dozed again on a fresh gap, so the link flapped and the status line
     * announced a different offline time every few seconds.
     */
    val sessionUntilMs: Long = 0L,
)

/**
 * Decides whether the WhatsApp link may drop, from the same clocks the behaviour
 * already runs on.
 *
 * Pure and Android-free on purpose: the interesting cases here are "what happens at
 * 03:00 with a conversation still running" and "what happens when the phone wakes
 * up ninety minutes late", and neither is worth an instrumented test.
 *
 * The one rule that outranks everything: a link that is mid-turn is never dropped.
 * Everything else is a schedule, and a schedule that cuts a sentence in half is
 * worse than a schedule that runs a minute long.
 */
class LinkPowerPolicy(
    private val timing: HumanTimingPolicy = HumanTimingPolicy(),
    private val random: Random = Random.Default,
) {
    /**
     * How long the bot keeps the link up after it has answered, drawn within
     * [LISTEN_JITTER] of the configured minutes.
     *
     * This is the window in which a real conversation happens. The contact reads,
     * types, sends — and none of that is visible to a disconnected client, so the
     * link has to outlive the dot by a good margin or the second message of every
     * exchange would wait for the next session. It is deliberately generous
     * compared to the presence tail: going offline as a *signal* takes seconds,
     * going offline as a *transport* costs the rest of the conversation.
     */
    fun listenWindowMs(configuredMinutes: Int): Long {
        val minutes =
            configuredMinutes.coerceIn(MIN_LISTEN_MINUTES, MAX_LISTEN_MINUTES).toLong()
        val base = minutes * 60_000L
        val spread = (base * LISTEN_JITTER).roundToLong()
        if (spread <= 0L) return base
        return base - spread + random.nextLong(2 * spread + 1)
    }

    /**
     * How long low mode waits between self-initiated online sessions.
     *
     * Shorter than [HumanTimingPolicy.selfSessionGapMs], and that is the entire
     * trade: in low mode this gap is also the worst-case time before an unanswered
     * message is even *seen*, so the sessions have to come around often enough that
     * "she looks at her phone every couple of hours" stays true as a reply latency
     * and not just as a presence pattern.
     */
    fun lowSessionGapMs(): Long =
        LOW_SESSION_GAP_MIN_MS +
            random.nextLong(LOW_SESSION_GAP_MAX_MS - LOW_SESSION_GAP_MIN_MS + 1)

    /**
     * The plan for right now.
     *
     * [manualWakeUntilMs] is the operator tapping the status line: it forces the
     * link up, through the quiet hours if need be, until it lapses.
     *
     * [sleepWakeAtMs] is the instant a previous call already drew for the end of the
     * quiet hours, or `0` for "not drawn yet". It has to be carried in rather than
     * re-derived, because [HumanTimingPolicy.sleepWakeInMs] re-rolls its jitter on
     * every call: without it the morning would move by up to two hours each time the
     * plan was revisited, and the armed alarm would never match the published time.
     */
    fun plan(
        nowMs: Long,
        settings: EngineSettingsSnapshot,
        activity: LinkActivity,
        manualWakeUntilMs: Long = 0L,
        sleepWakeAtMs: Long = 0L,
        dozeWakeAtMs: Long = 0L,
        sessionUntilMs: Long = 0L,
        linkIsUp: Boolean = true,
    ): LinkPlan {
        // Mid-turn beats every schedule below — but only as a reason not to *drop* a link
        // that is already carrying the sentence. It is not a reason to raise one.
        //
        // Unqualified, this was the rule that made the whole feature ornamental: the engine
        // sets `busy` the moment a turn starts, so a reply whose drawn delay happened to
        // come due inside a doze pulled the socket back up and sent — hours before the time
        // the status line had promised, and with a long press explicitly asking for the
        // opposite. The engine now waits for the link instead (see [LinkPowerFeed.awaitCarrying]),
        // which is what makes this qualifier safe: a turn can no longer be running while
        // the link is down, so the only case this excludes is the one that was a bug.
        if (activity.busy && linkIsUp) return LinkPlan(LinkState.AWAKE, null, sessionUntilMs)

        // Instant is a promise the transport has to keep too. There is no reading pause, no
        // pickup ladder and no online session in this preset — the bot answers the moment a
        // message lands — so there is also nothing for a quiet hour or a doze to be a pause
        // *between*. A link that is down at 03:00 would turn "instant" into "in six hours".
        // Default mode outside the quiet hours is the same story for a duller reason: the
        // link is simply always up there.
        val alwaysAwake =
            settings.replyPreset == ReplyPreset.INSTANT ||
                (
                    settings.effectivePowerMode == PowerMode.DEFAULT &&
                        !timing.isSleeping(nowMs, settings)
                )

        if (nowMs < manualWakeUntilMs) {
            // The later of the two deadlines, not the manual one: a message that arrives
            // during a hand-started window pushes the real end out past it, and reporting
            // the manual instant would have the status line announce an offline time the
            // link is not going to keep. Null when nothing but the override is holding the
            // link up anyway — an "offline at" for a link that is never going offline is
            // worse than no number at all.
            val until =
                if (alwaysAwake) {
                    null
                } else {
                    maxOf(manualWakeUntilMs, activity.listenUntilMs, sessionUntilMs)
                }
            return LinkPlan(LinkState.AWAKE, until, sessionUntilMs)
        }

        if (settings.replyPreset == ReplyPreset.INSTANT) return LinkPlan(LinkState.AWAKE, null)

        // A deliberate promise is an explicit session, not personality-driven outreach. It may
        // therefore open the link before the normal sleep/doze schedule, including quiet hours.
        if (activity.scheduledFollowUpAtMs in 1..nowMs) {
            val until = nowMs + listenWindowMs(settings.lowListenMinutes)
            return LinkPlan(LinkState.AWAKE, until, sessionUntilMs = until)
        }

        // Quiet hours outrank the mode: default mode sleeps at night too. That is the
        // whole reason this exists in default mode at all — the bot already answers
        // nothing between 00:30 and 08:30, it simply used to burn a pinned CPU doing it.
        if (timing.isSleeping(nowMs, settings)) {
            // Exactly the wait a message arriving now would have sat out, so the link
            // is back at the moment the behaviour would have answered anyway.
            val wakeAt =
                if (sleepWakeAtMs > nowMs) {
                    sleepWakeAtMs
                } else {
                    nowMs + (timing.sleepWakeInMs(nowMs, settings) ?: 0L)
                }
            return LinkPlan(
                LinkState.SLEEPING,
                wakeAtMs = listOf(wakeAt, activity.scheduledFollowUpAtMs.takeIf { it > nowMs })
                    .filterNotNull()
                    .minOrNull(),
            )
        }

        if (settings.effectivePowerMode == PowerMode.DEFAULT) return LinkPlan(LinkState.AWAKE, null)

        // Still in the tail of a conversation, or inside a session that already opened.
        val holding = maxOf(activity.listenUntilMs, sessionUntilMs)
        if (nowMs < holding) return LinkPlan(LinkState.AWAKE, holding, sessionUntilMs)

        // No session published by the engine: doze on a gap this policy draws itself.
        //
        // It used to stay AWAKE here, on the reasoning that dozing without an alarm is
        // worse than a few awake seconds — and that was correct about the alarm and
        // wrong about the "few seconds". The engine only ever publishes a session from
        // its self-session loop, which production does not run, so `nextSessionAtMs`
        // stayed 0 for good and low mode never dozed once: the link was up around the
        // clock and only the quiet hours ever took it down. The drawn instant below is
        // an alarm like any other, and the controller carries it across re-plans so it
        // does not move every time the decision is revisited.
        //
        // A doze that has *come due* keeps its own instant instead of falling through to
        // a fresh draw. Re-drawing there was the bug that made low mode look possessed:
        // `dozeWakeAtMs > nowMs` is false at exactly the moment the alarm fires, so the
        // link answered its own wake-up by dozing again on a new random gap, the branch
        // below never ran, and the status line announced a different offline time every
        // time anything poked the loop. Low mode was "offline until you tap", not "up
        // every hour and a half".
        val ordinaryNextSession =
            when {
                activity.nextSessionAtMs > 0L -> activity.nextSessionAtMs
                dozeWakeAtMs > 0L -> dozeWakeAtMs
                else -> nowMs + lowSessionGapMs()
            }
        val nextSession =
            listOf(
                ordinaryNextSession,
                activity.scheduledFollowUpAtMs.takeIf { it > 0L },
            ).filterNotNull().minOrNull() ?: ordinaryNextSession

        // The session is due — or overdue, because the alarm fired late or the device
        // was suspended past it. The link comes up for one listening window: long enough
        // to receive what was sent while it was down, answer it, and see the reply.
        if (nowMs >= nextSession) {
            val until = nowMs + listenWindowMs(settings.lowListenMinutes)
            return LinkPlan(LinkState.AWAKE, until, sessionUntilMs = until)
        }

        return LinkPlan(LinkState.DOZING, wakeAtMs = nextSession)
    }

    companion object {
        const val MIN_LISTEN_MINUTES = 2
        const val MAX_LISTEN_MINUTES = 30
        const val DEFAULT_LISTEN_MINUTES = 10

        /** ±20%, applied silently — the setting names a target, not a stopwatch. */
        private const val LISTEN_JITTER = 0.20

        private const val MINUTE = 60_000L

        /**
         * The low-mode session gap. Chosen shorter than the behavioural 3–5 h so that
         * low mode changes how *often* she looks rather than turning her into someone
         * who is unreachable for half a day; see [lowSessionGapMs].
         */
        private const val LOW_SESSION_GAP_MIN_MS = 90 * MINUTE
        private const val LOW_SESSION_GAP_MAX_MS = 180 * MINUTE
    }
}
