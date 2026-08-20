package de.totec.doppel.engine

import java.time.Instant
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One planned online-pickup window.
 *
 * [minDelayMs] is the floor a later message may shorten the wait down to — never
 * below it. Without that floor a chatty contact could talk a cold, hour-long
 * pickup down to seconds.
 */
data class OnlineDelayPlan(
    val delayMs: Long,
    val minDelayMs: Long,
    /**
     * The longest wait the CURRENT settings and state still allow — the ceiling of
     * the band this plan was rolled from, not the roll itself.
     *
     * An already-armed window is clamped to it every time new input arrives, so a
     * window can never outlive the settings it was rolled under. Switching the
     * preset to instant gives a ceiling of 0 and collapses a human delay that is
     * still counting down, instead of making the contact sit out the old wait.
     * Using the ceiling rather than the fresh roll is deliberate: taking the new
     * roll would let a chatty contact re-roll the dice on every message and keep
     * the minimum of all draws.
     */
    val maxDelayMs: Long = delayMs,
)

/**
 * How live the conversation is at this instant, and whether it has just been closed by its own
 * stamina.
 *
 * This is the second axis of the pickup. The first — how long the dot has been out — says how far
 * away the phone is; this one says how *engaged* the person holding it is, and it is what moves the
 * peak of the delay curve rather than its edges.
 *
 * [cooling] exists because [heat] on its own has a failure mode with no natural end. Two accounts
 * answering each other keep each other's heat at 1 indefinitely: every reply refreshes the other
 * side's "answered seconds ago", the curve stays peaked at its fast end for both, and the pair sits
 * online forever. Real conversations do not end because the other person got slower, they end
 * because someone puts the phone down. That is [cooling].
 */
data class ExchangeTempo(
    /** 0 = nothing has been said in a while, 1 = the last reply went out seconds ago. */
    val heat: Double = 0.0,
    /** True while a burst that ran out of stamina is being sat out. */
    val cooling: Boolean = false,
)

/**
 * How long the bot waits before it "comes online" and answers, how long it reads,
 * and how fast it types.
 *
 * The whole model hangs off one question: **is the dot on?** [OnlineSessionClock]
 * answers it, and everything here is expressed as "how long has she been offline".
 *
 *  - online, this chat in front of her → no pickup wait at all. The request goes
 *    out the moment the message lands, and the only thing between it and the
 *    answer is [readingFloorMs].
 *  - offline → a wait that grows in steps with the time since she put the phone
 *    down, and the request goes out at the *end* of it. That is not a detail: it
 *    is what lets three messages typed in one breath become one model call
 *    instead of three, and it is why the wait is never added on top of the model
 *    call — the two never overlap.
 */
class HumanTimingPolicy(
    private val random: Random = Random.Default,
) {
    /**
     * Plan the pickup wait: how long until she looks at her phone.
     *
     * The ladder, measured from the moment the dot went out ([offlineForMs]):
     *
     * | state                    | floor | peak      | ceiling |
     * |--------------------------|-------|-----------|---------|
     * | online, chat open        | none  | none      | none    |
     * | offline < 5 min          | 10 s  | ~1 min    | 2 min   |
     * | offline, still in session| 10 s  | ~6 min    | 10 min  |
     * | offline, session over    | 10 s  | ~20 min   | 30 min  |
     * | cooling off              | 5 min | ~25 min   | 30 min  |
     * | asleep                   | until she wakes    |        |
     *
     * Every band used to be a flat draw between its two edges, which got the
     * averages right and the shape wrong. A flat band cannot produce the thing
     * people actually do — glance at the phone at the exact moment a message
     * lands — without also making the *typical* answer that fast, because a
     * uniform draw has no typical value at all. So the floor of every band is now
     * the same ten seconds and the ceilings are untouched; what separates the
     * bands is where the curve peaks ([triangular]). A cold contact still almost
     * always waits about twenty minutes, and once in a few hundred messages the
     * answer comes back in under a minute because she happened to be looking.
     *
     * [tempo] moves that peak. The band says how far away the phone is; the tempo
     * says how engaged the person holding it is, and a reply that went out seconds
     * ago drags the peak down to the fast end of whatever band applies. That is
     * what lets a conversation *become* live rather than only being live when the
     * dot happens to be on — and [ExchangeTempo.cooling] is what makes it end
     * again.
     *
     * Reading time is deliberately *not* part of this. It used to be folded in
     * here, which double-counted it against [readingFloorMs] at the other end of
     * the turn. The pickup answers "when does she pick the phone up", the floor
     * answers "how fast may the first visible sign follow" — one each.
     */
    fun pickupPlan(
        nowMs: Long,
        offlineForMs: Long,
        inSession: Boolean,
        settings: EngineSettingsSnapshot,
        immediate: Boolean = false,
        chatOpen: Boolean = false,
        tempo: ExchangeTempo = ExchangeTempo(),
    ): OnlineDelayPlan {
        if (settings.replyPreset == ReplyPreset.INSTANT || immediate) {
            return OnlineDelayPlan(0L, 0L, 0L)
        }

        // The exchange ran at conversation speed for as long as anyone keeps that up, so the phone
        // goes down. Checked before the open chat on purpose, and it is the only thing in this
        // function that overrides it: "the dot is on" is exactly the condition that would otherwise
        // let two fast correspondents answer each other with no wait at all, forever, which is the
        // runaway the cooldown exists to break. Sleep still wins over it, below.
        if (tempo.cooling) {
            sleepDelayMs(nowMs, settings)?.let { return OnlineDelayPlan(it, it, it) }
            return notPastBedtime(nowMs, draw(COOLDOWN_BAND, heat = 0.0), settings)
        }

        // The dot is on and this is the chat she is in. There is nothing to "pick
        // up": the message lands in front of her eyes, so it is read at once and
        // the request goes out at once.
        if (chatOpen) return OnlineDelayPlan(0L, 0L, 0L)

        // She put the phone down moments ago — still within reach, still checking
        // it. Placed before the sleep window on purpose: someone who was online at
        // 03:00 is demonstrably awake, whatever the schedule says.
        //
        // But only for a while. Every reply refreshes "was online just now", so an
        // unbounded exemption meant one contact who kept typing could hold her in
        // this band all night and the sleep window never took effect at all — which
        // is a far worse tell than answering ten minutes past bedtime. The override
        // therefore covers staying up late ([STAYED_UP_GRACE_MS] past the start of
        // the window) and nothing beyond it.
        val justOffline = offlineForMs < JUST_OFFLINE_MS
        if (justOffline && sleepStartedMsAgo(nowMs, settings).let { it == null || it < STAYED_UP_GRACE_MS }) {
            return draw(JUST_OFFLINE_BAND, tempo.heat)
        }

        sleepDelayMs(nowMs, settings)?.let { return OnlineDelayPlan(it, it, it) }

        // Past this point the window is not running yet — so every band below can still
        // draw a wait that ends inside it, and [notPastBedtime] is what stops it.
        if (justOffline) return notPastBedtime(nowMs, draw(JUST_OFFLINE_BAND, tempo.heat), settings)

        // Still inside the behavioural session: the phone is nearby and gets picked
        // up on its own every few minutes, but she is not watching this chat.
        if (inSession) return notPastBedtime(nowMs, draw(IN_SESSION_BAND, tempo.heat), settings)

        // The session is over. She is doing something else and will notice at the
        // next time she looks — which, left alone, is also when the self-initiated
        // online session in [selfSessionGapMs] would have come around anyway.
        return notPastBedtime(nowMs, draw(AWAY_BAND, tempo.heat), settings)
    }

    /**
     * A wait drawn before bedtime that would elapse after it becomes a wait until morning.
     *
     * The bands are drawn against the clock as it is now, and the away band is half an hour
     * wide, so a message at ten past midnight could have its answer scheduled for a quarter
     * to one — inside a sleep window that had not started when the dice were rolled. Two
     * things then went wrong at once: the account answered in the middle of a night it is
     * supposed to be unreachable in, and the transport had already dropped the link for
     * those same quiet hours, so the answer went into a socket that was not there.
     *
     * Only ever called with the window still ahead. Inside it, the "she stayed up late"
     * grace above has already decided, and pushing that to morning would take the grace
     * back out again.
     */
    private fun notPastBedtime(
        nowMs: Long,
        plan: OnlineDelayPlan,
        settings: EngineSettingsSnapshot,
    ): OnlineDelayPlan {
        val rest = sleepDelayMs(nowMs + plan.delayMs, settings) ?: return plan
        val total = plan.delayMs + rest
        return OnlineDelayPlan(total, total, total)
    }

    /**
     * Shorten a still-pending pickup when another message arrives: trim a random
     * slice off the remaining time, never below a human floor and never below the
     * floor chosen when the window opened. The window is only ever pulled in,
     * never pushed out — that is what keeps a running conversation from sliding
     * back into a long wait every time the contact types again.
     */
    fun reduceOnlineDelay(
        restMs: Long,
        floorMs: Long = 0L,
    ): Long {
        val floor = maxOf(floorMs, between(3 * SECOND, 10 * SECOND))
        if (restMs <= floor) return restMs.coerceAtLeast(0L)
        val cut = (restMs * rand(0.1, 0.3)).roundToLong()
        return maxOf(floor, restMs - cut)
    }

    /**
     * How long "typing…" runs before a bubble goes out, at the speed people
     * actually reach on a phone.
     *
     * The rate is a setting ([EngineSettingsSnapshot.typingWordsPerMinute]) with a
     * measured default. Palin et al., *"How do People Type on Mobile Devices?
     * Observations from a Study with 37,000 Volunteers"* (MobileHCI 2019, Aalto /
     * Cambridge) put the mean of 37,370 participants at 36.2 WPM, and the fastest
     * cohort — 10-19-year-olds typing with two thumbs — around 40. The default
     * here sits at [DEFAULT_TYPING_WPM], deliberately at the quick end of that
     * range rather than on the population mean: the mean is dragged down by
     * one-finger and older typists, and a chat persona that types like the average
     * of everyone reads as sluggish.
     *
     * WPM counts a "word" as five characters *including* the space, so the rate
     * converts directly: WPM * 5 characters per minute. Every character counts,
     * spaces included — they are keystrokes.
     *
     * On top of the rate: a short beat before the first keystroke (finding the
     * thought, not the keyboard) and a per-bubble speed jitter, because nobody
     * types two messages at exactly the same pace. The ceiling is a guard against
     * one runaway bubble holding the queue, not a style choice.
     */
    fun typingDelayMs(
        text: String,
        settings: EngineSettingsSnapshot,
    ): Long {
        if (settings.replyPreset == ReplyPreset.INSTANT) return 0
        val characters = text.length.coerceAtLeast(1)
        val msPerCharacter = msPerCharacter(settings.typingWordsPerMinute)
        val keystrokes = characters * msPerCharacter * rand(1.0 - SPEED_JITTER, 1.0 + SPEED_JITTER)
        val delay = (keystrokes + between(TYPING_LEAD_IN_MIN_MS, TYPING_LEAD_IN_MAX_MS)).roundToLong()
        return delay.coerceIn(TYPING_MIN_MS, TYPING_MAX_MS)
    }

    /**
     * The pause between two bubbles of the same answer: send, glance at it, start
     * the next one. Typing does not run through it — the indicator has to drop, or
     * a multi-bubble answer reads as one uninterrupted machine burst.
     */
    fun interBubbleGapMs(settings: EngineSettingsSnapshot): Long =
        if (settings.replyPreset == ReplyPreset.INSTANT) 0L else between(700L, 1_400L)

    /**
     * How long "schreibt…" keeps running after the contact interrupts her.
     *
     * The indicator used to drop in the same millisecond the new message landed,
     * which is a tell in the opposite direction of the usual one: no human sees a
     * message arrive and stops typing instantly. They finish the word, look up,
     * *then* stop. This is that beat — a fresh draw per interrupt, because a fixed
     * offset is a fingerprint of its own.
     *
     * Drawn from a quarter of [EngineSettingsSnapshot.typingStopDelayMs] up to the
     * full value, so the 2 s default lands in the 0.5–2 s band. Zero switches the
     * behaviour off and stops the indicator immediately.
     */
    fun typingStopDelayMs(settings: EngineSettingsSnapshot): Long {
        if (settings.replyPreset == ReplyPreset.INSTANT) return 0L
        val ceiling = settings.typingStopDelayMs.coerceIn(0L, MAX_TYPING_STOP_DELAY_MS)
        if (ceiling <= 0L) return 0L
        return between(ceiling / 4, ceiling)
    }

    /**
     * How long she "types" the correction before the edit goes out.
     *
     * A self-edit is a real edit: she taps the message, fixes the typo and sends it
     * again, and WhatsApp shows the indicator while she does. The old fixed
     * 0.7–1.8 s did not care whether the bubble was two words or two hundred, so a
     * long message was corrected implausibly fast. It is the same per-character
     * model as [typingDelayMs] — only divided, because fixing one slip is not
     * retyping the message. The divisor is
     * [EngineSettingsSnapshot.selfEditDelayDivisor] (2 by default: half the time
     * the bubble itself took).
     */
    fun selfEditDelayMs(
        text: String,
        settings: EngineSettingsSnapshot,
    ): Long {
        val typing = typingDelayMs(text, settings)
        if (typing <= 0L) return 0L
        val divisor = settings.selfEditDelayDivisor.coerceIn(MIN_SELF_EDIT_DIVISOR, MAX_SELF_EDIT_DIVISOR)
        return (typing / divisor).roundToLong().coerceAtLeast(SELF_EDIT_MIN_MS)
    }

    /**
     * The shortest time that may pass between the message arriving and the first
     * thing the contact can see — reading it, in other words.
     *
     * This is a floor and nothing else. The model call already takes seconds, and
     * those seconds *are* the reading time, which is why the request goes out
     * immediately instead of sitting behind an artificial delay. Only when the
     * answer comes back faster than a person could plausibly have read the message
     * is the difference waited out; a slower call uses none of this.
     *
     * The rate is per word and it accelerates with length, which is how reading
     * actually works: a five-word message is one glance plus a moment of "what do
     * I say to that", and the fixed cost of that moment dominates. A two-hundred
     * word wall is skimmed, and the per-word cost drops as the eye stops reading
     * every one of them. So the rate ramps from
     * [EngineSettingsSnapshot.readingWordsPerSecond] up to twice that, reaching
     * full speed at [READING_FULL_SPEED_WORDS] words:
     *
     * | words | effective rate | floor  |
     * |-------|----------------|--------|
     * | 5     | ~10 w/s        | ~0.5 s |
     * | 50    | 12.5 w/s       | ~4 s   |
     * | 200   | 20 w/s         | ~10 s  |
     * | 500   | 20 w/s (cap)   | ~20 s (cap) |
     */
    fun readingFloorMs(
        text: String,
        settings: EngineSettingsSnapshot,
    ): Long {
        if (settings.replyPreset == ReplyPreset.INSTANT) return 0L
        val words = wordCount(text)
        if (words == 0) return 0L
        val base = settings.readingWordsPerSecond.coerceAtLeast(1).toDouble()
        val ramp = (words.toDouble() / READING_FULL_SPEED_WORDS).coerceIn(0.0, 1.0)
        // 1x at a glance, 2x once it is being skimmed.
        val wordsPerSecond = base * (1.0 + ramp)
        val reading = words / wordsPerSecond * SECOND * rand(1.0 - READING_JITTER, 1.0 + READING_JITTER)
        return (reading + between(READING_NOTICE_MIN_MS, READING_NOTICE_MAX_MS))
            .roundToLong()
            .coerceIn(READING_FLOOR_MIN_MS, READING_FLOOR_MAX_MS)
    }

    /**
     * How long until she next picks the phone up for no reason at all.
     *
     * People look at their phone without being prompted, and a persona that is
     * only ever online in the seconds around an answer has an obvious shape: the
     * dot is a perfect function of the contact's own messages. These sessions
     * break that. They also do the timing model a favour — the ladder in
     * [pickupPlan] resets from every online window, so a self-initiated one drags
     * a cold chat back down to the fast bands without anyone having written
     * anything.
     *
     * Measured from the end of the *last* online window, whatever opened it. That
     * is what keeps the two from overlapping: an answer at 14:00 pushes the next
     * self-session to 17:00-19:00, rather than leaving a session scheduled for
     * 14:20 that would come online again twenty minutes after she was last there.
     */
    fun selfSessionGapMs(): Long = between(SELF_SESSION_GAP_MIN_MS, SELF_SESSION_GAP_MAX_MS)

    /** How long the dot stays on during one of those sessions: a look, not a stay. */
    fun selfSessionOnlineMs(): Long = between(SELF_SESSION_ONLINE_MIN_MS, SELF_SESSION_ONLINE_MAX_MS)

    /** True while the configured sleep window is running. */
    fun isSleeping(
        nowMs: Long,
        settings: EngineSettingsSnapshot,
    ): Boolean = sleepDelayMs(nowMs, settings) != null

    /**
     * How long until she is up again, or null when the window is not running.
     *
     * Exactly the wait a message arriving right now would sit out, jitter included,
     * so the link comes back at the same moment the behaviour would have answered
     * anyway. Every call re-rolls that jitter — a caller arming an alarm must draw
     * once and keep the value, or the wake time would drift on every re-evaluation.
     */
    fun sleepWakeInMs(
        nowMs: Long,
        settings: EngineSettingsSnapshot,
    ): Long? = sleepDelayMs(nowMs, settings)

    /**
     * One pickup drawn from [band], with the peak moved by [heat].
     *
     * The reduction floor is deliberately *not* the floor of the draw. The draw may land at ten
     * seconds because she happened to be looking; a contact who keeps typing may not talk a cold
     * half-hour window down to ten seconds by nagging, which is the difference between looking
     * human and looking like something that answers faster the more you poke it. So the floor
     * handed to [reduceOnlineDelay] stays at the band's own minimum — except when the draw already
     * came in under it, in which case the draw is the honest floor and raising it would push the
     * window back out.
     */
    private fun draw(
        band: Band,
        heat: Double,
    ): OnlineDelayPlan {
        // Heat picks *which* curve this draw comes from rather than sliding one curve along, and
        // the reason is that sliding it does not work: moving the peak of a ten-minute band down to
        // forty seconds still leaves the whole right half of the band in play, so the typical wait
        // barely moves and a "live" exchange is live in name only. Choosing between a wide curve
        // and a narrow one gives the fast case a real shape while leaving the slow one exactly as
        // it was — and it keeps the band's ceiling untouched at every heat, because a live
        // conversation that occasionally goes quiet for eight minutes is a conversation, not a bug.
        val warmth = heat.coerceIn(0.0, 1.0)
        val live = warmth > 0.0 && random.nextDouble() < warmth
        val delay =
            if (live) {
                triangular(band.floorMs, band.fastModeMs, band.liveMaxMs)
            } else {
                triangular(band.floorMs, band.slowModeMs, band.maxMs)
            }
        return OnlineDelayPlan(delay, minOf(delay, band.reductionFloorMs), band.maxMs)
    }

    /**
     * One draw from a triangular distribution over `[minMs, maxMs]` peaking at [modeMs].
     *
     * Triangular rather than one of the prettier bell shapes for two reasons that matter here: it
     * is bounded at both ends, so a band's ceiling is a real ceiling and not a tail that
     * occasionally answers a WhatsApp message four hours late; and it inverts in closed form, so a
     * draw costs one uniform and no rejection loop on a path that runs for every message.
     *
     * A mode at either edge degenerates to the matching right triangle rather than misbehaving,
     * which is what the cooldown band leans on.
     */
    private fun triangular(
        minMs: Long,
        modeMs: Long,
        maxMs: Long,
    ): Long {
        if (maxMs <= minMs) return minMs
        val min = minMs.toDouble()
        val max = maxMs.toDouble()
        val mode = modeMs.coerceIn(minMs, maxMs).toDouble()
        val width = max - min
        val split = (mode - min) / width
        val u = random.nextDouble()
        val value =
            if (u < split) {
                min + sqrt(u * width * (mode - min))
            } else {
                max - sqrt((1.0 - u) * width * (max - mode))
            }
        return value.roundToLong().coerceIn(minMs, maxMs)
    }

    /**
     * One rung of the pickup ladder.
     *
     * [floorMs] and [maxMs] bound what may be drawn; [slowModeMs] and [fastModeMs] are where the
     * peak sits at the two extremes of [ExchangeTempo.heat]. [reductionFloorMs] is the separate,
     * higher floor that a *pending* window may be shortened to — see [draw].
     */
    private data class Band(
        val floorMs: Long,
        val slowModeMs: Long,
        val fastModeMs: Long,
        /** Ceiling of the slow curve, and the band's ceiling outright. Never moves with heat. */
        val maxMs: Long,
        /** Ceiling of the narrow curve a live exchange draws from. */
        val liveMaxMs: Long,
        val reductionFloorMs: Long,
    )

    /**
     * How long the sleep window has been running, or null when it is not running.
     *
     * Only the pickup uses it, to decide whether recent activity still counts as "she is up late"
     * or has become "she never sleeps".
     */
    private fun sleepStartedMsAgo(
        nowMs: Long,
        settings: EngineSettingsSnapshot,
    ): Long? {
        if (sleepDelayMs(nowMs, settings) == null) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(settings.timezone)
        val minutes = now.hour * 60 + now.minute
        val start = settings.sleepStartMinutes.coerceIn(0, DAY_MINUTES - 1)
        val elapsed = if (minutes >= start) minutes - start else (DAY_MINUTES - start) + minutes
        return elapsed * MINUTE + now.second * SECOND
    }

    private fun sleepDelayMs(
        nowMs: Long,
        settings: EngineSettingsSnapshot,
    ): Long? {
        val now = Instant.ofEpochMilli(nowMs).atZone(settings.timezone)
        val minutes = now.hour * 60 + now.minute
        // A stored minute outside the day would make the comparisons below meaningless rather
        // than merely wrong — clamp first, decide afterwards.
        val start = settings.sleepStartMinutes.coerceIn(0, DAY_MINUTES - 1)
        val end = settings.sleepEndMinutes.coerceIn(0, DAY_MINUTES - 1)
        // Same minute at both ends is an empty window, i.e. no sleep at all. Read as a wrapping
        // window it was the opposite: every minute counted as sleeping, and the bot went quiet
        // permanently with no way to tell from the outside.
        if (start == end) return null
        val sleeping =
            if (start < end) minutes in start until end else minutes >= start || minutes < end
        if (!sleeping) return null
        val minutesUntilEnd =
            if (minutes < end) end - minutes else (DAY_MINUTES - minutes) + end
        // She does not answer the second her alarm goes off.
        return minutesUntilEnd * MINUTE - now.second * SECOND + between(5 * MINUTE, 120 * MINUTE)
    }

    private fun between(
        minMs: Long,
        maxMs: Long,
    ): Long = if (maxMs <= minMs) minMs else random.nextLong(minMs, maxMs + 1)

    private fun rand(
        min: Double,
        max: Double,
    ): Double = min + random.nextDouble() * (max - min)

    companion object {
        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE
        private const val DAY_MINUTES = 24 * 60

        /** Words, for reading purposes: runs of non-space, however they are spelled. */
        fun wordCount(text: String): Int {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return 0
            return trimmed.split(WHITESPACE).size
        }

        /** WPM counts five characters per word, spaces included. */
        fun msPerCharacter(wordsPerMinute: Int): Double =
            60_000.0 / (wordsPerMinute.coerceAtLeast(1) * 5.0)

        /**
         * Quick end of the measured mobile range rather than the population mean of
         * 36.2 WPM — see [typingDelayMs].
         */
        const val DEFAULT_TYPING_WPM = 70

        /**
         * Words per second for a short message, before the length ramp in
         * [readingFloorMs] speeds it up. Reading prose is slower than this; a chat
         * message is not prose, it is a glance at two lines you were already
         * expecting.
         */
        const val DEFAULT_READING_WORDS_PER_SECOND = 10

        /** Length at which the reading rate has reached its ceiling of 2x the base. */
        private const val READING_FULL_SPEED_WORDS = 200.0

        /** How long "just put the phone down" lasts. */
        /**
         * How far past the start of the sleep window "she was online a minute ago" still beats the
         * schedule. Ninety minutes is a person finishing a conversation before bed; past that, no
         * amount of incoming messages keeps her up, because a persona that is reachable at every
         * hour of every night is not a person.
         */
        private const val STAYED_UP_GRACE_MS = 90 * MINUTE

        private const val JUST_OFFLINE_MS = 5 * MINUTE

        /**
         * The shortest pickup any band may draw.
         *
         * Shared by all of them on purpose. It is not "how fast she answers" — the peak of each
         * band is that — it is "how fast she *can* answer if the message happens to land while she
         * is holding the phone", and that number does not depend on how long she was away. Ten
         * seconds rather than the two or three the reduction floor allows, because this is an
         * unprompted glance and not a conversation already in flight.
         */
        private const val PICKUP_FLOOR_MS = 10 * SECOND

        /** Phone still in reach, screen probably still warm. */
        private val JUST_OFFLINE_BAND =
            Band(
                floorMs = PICKUP_FLOOR_MS,
                slowModeMs = 75 * SECOND,
                fastModeMs = 20 * SECOND,
                maxMs = 2 * MINUTE,
                liveMaxMs = 45 * SECOND,
                reductionFloorMs = 30 * SECOND,
            )

        /** Phone nearby and picked up on its own every few minutes, but not for this chat. */
        private val IN_SESSION_BAND =
            Band(
                floorMs = PICKUP_FLOOR_MS,
                slowModeMs = 6 * MINUTE,
                fastModeMs = 40 * SECOND,
                maxMs = 10 * MINUTE,
                liveMaxMs = 2 * MINUTE,
                reductionFloorMs = 2 * MINUTE,
            )

        /** Doing something else entirely; the message waits for the next time she looks. */
        private val AWAY_BAND =
            Band(
                floorMs = PICKUP_FLOOR_MS,
                slowModeMs = 20 * MINUTE,
                fastModeMs = 3 * MINUTE,
                maxMs = 30 * MINUTE,
                liveMaxMs = 6 * MINUTE,
                reductionFloorMs = 10 * MINUTE,
            )

        /**
         * The band a spent burst is sat out in.
         *
         * The only one with a floor of its own: the whole point is that the phone is down, so the
         * ten-second "she happened to be looking" draw must not be reachable here — it is the one
         * escape hatch that would let a runaway exchange restart itself on the next message. Both
         * modes are identical because heat is zero throughout, so the shape is fixed no matter what
         * the other side does.
         */
        private val COOLDOWN_BAND =
            Band(
                floorMs = 5 * MINUTE,
                slowModeMs = 25 * MINUTE,
                fastModeMs = 25 * MINUTE,
                maxMs = 30 * MINUTE,
                liveMaxMs = 30 * MINUTE,
                reductionFloorMs = 5 * MINUTE,
            )

        private const val SELF_SESSION_GAP_MIN_MS = 3 * HOUR
        private const val SELF_SESSION_GAP_MAX_MS = 5 * HOUR
        private const val SELF_SESSION_ONLINE_MIN_MS = 25 * SECOND
        private const val SELF_SESSION_ONLINE_MAX_MS = 35 * SECOND

        /** Per-bubble speed variation, so two bubbles never take the same pace. */
        private const val SPEED_JITTER = 0.18

        private const val TYPING_LEAD_IN_MIN_MS = 300L
        private const val TYPING_LEAD_IN_MAX_MS = 1_100L

        /** Even "ok" costs a moment; one long bubble must not own the queue. */
        private const val TYPING_MIN_MS = 1_400L
        private const val TYPING_MAX_MS = 30 * SECOND

        /**
         * Default ceiling of the post-interrupt typing pause — see
         * [typingStopDelayMs]. The draw starts at a quarter of it, so the default
         * band is 0.5–2 s.
         */
        const val DEFAULT_TYPING_STOP_DELAY_MS = 2 * SECOND
        const val MAX_TYPING_STOP_DELAY_MS = 10 * SECOND

        /** Divides the bubble's own typing time — see [selfEditDelayMs]. */
        const val DEFAULT_SELF_EDIT_DIVISOR = 2.0
        const val MIN_SELF_EDIT_DIVISOR = 1.0
        const val MAX_SELF_EDIT_DIVISOR = 10.0

        /** Spotting the slip and reaching for the message takes this long regardless. */
        private const val SELF_EDIT_MIN_MS = 700L

        private const val READING_JITTER = 0.15

        /** Seeing that something arrived, before reading a word of it. */
        private const val READING_NOTICE_MIN_MS = 300L
        private const val READING_NOTICE_MAX_MS = 900L

        private const val READING_FLOOR_MIN_MS = 700L
        private const val READING_FLOOR_MAX_MS = 20 * SECOND

        private val WHITESPACE = Regex("\\s+")
    }
}
