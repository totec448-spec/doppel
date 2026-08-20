package de.totec.doppel.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The visible WhatsApp online dot, ported from the reference build's
 * `src/runtime/presence.ts`.
 *
 * Two rules make it look human, and both were violated before:
 *
 *  1. A connect must never turn the dot on. The native layer used to broadcast
 *     `available` right after the handshake, so the account was online for as
 *     long as the app ran — green dot, no activity, for hours. The dot is now
 *     driven from here alone: it goes on when a turn actually picks a chat up.
 *
 *  2. Going offline the millisecond the last bubble leaves is just as much of a
 *     tell as never going offline. A real person's dot lingers a few seconds
 *     after they put the phone down, so the last [release] starts a randomised
 *     [IDLE_OFF_MIN_MS]–[IDLE_OFF_MAX_MS] tail and only then freezes last-seen.
 *
 * [hold]/[release] are counted, so working through several queued chats in one
 * pickup window is ONE continuous online stretch instead of a blink per chat.
 *
 * Every transition is reported to [listener]. That is what stops the visible dot
 * and the timing model from drifting apart: the behavioural "is she in this chat"
 * is not a second, longer window that happens to be configured similarly — it is
 * this one.
 */
class PresenceCoordinator(
    private val scope: CoroutineScope,
    private val whatsapp: WhatsAppActions,
    private val clock: EngineClock = SystemEngineClock,
    private val random: Random = Random.Default,
    private val listener: Listener = Listener.NONE,
) {
    /** Told whenever the dot actually changes state, never on a no-op hold. */
    interface Listener {
        /** [chatJid] is `null` when she is online without being in any chat. */
        fun onWentOnline(chatJid: String?)

        fun onWentOffline()

        companion object {
            val NONE =
                object : Listener {
                    override fun onWentOnline(chatJid: String?) = Unit

                    override fun onWentOffline() = Unit
                }
        }
    }

    private val mutex = Mutex()
    private var holds = 0
    private var online = false
    private var idleJob: Job? = null

    /** The chat whose jid was last passed in; presence itself is account-global. */
    private var lastChatJid: String = ""

    /**
     * She just opened a chat: show online and keep it on until the matching
     * [release]. Wrapping the whole read/generate/type/send stretch in one hold
     * is what stops a 20 s generation from blinking her offline mid-reply.
     */
    suspend fun hold(chatJid: String) = hold(chatJid, inChat = chatJid)

    /**
     * One self-initiated look at the phone: the dot goes on for [durationMs] with
     * no chat singled out, then straight back off. No tail — the tail models
     * putting a phone down after a conversation, and there was none.
     *
     * Reported as "online, no chat in particular", so a message arriving during
     * those seconds is answered as immediately as one arriving mid-conversation.
     * That is the point of it: it drags the pickup ladder back to its fast bands
     * without anything having been said.
     */
    suspend fun showOnlineBriefly(durationMs: Long) {
        val chatJid = mutex.withLock { lastChatJid }
        hold(chatJid, inChat = null)
        try {
            clock.delay(durationMs)
        } finally {
            // The whole point of this window is that it ends. Releasing through the mutex from a
            // cancelled coroutine throws before the hold is returned, and the dot then never goes
            // out again — so this one release is not allowed to be cancelled.
            withContext(NonCancellable) { release(chatJid, tailMs = 0L) }
        }
    }

    /**
     * The turn is done. Once the last hold is gone she stays online for a short
     * randomised tail and then her last-seen freezes there.
     */
    suspend fun release(
        chatJid: String,
        tailMs: Long = idleOffMs(),
    ) {
        mutex.withLock {
            if (holds > 0) holds -= 1
            if (holds > 0 || !online) return@withLock
            idleJob?.cancel()
            idleJob = armIdleOff(chatJid, tailMs)
        }
    }

    /** Force the dot off now — used when the bot is paused or shuts down. */
    suspend fun forceOffline() {
        // The jid is read under the lock together with the state it belongs to. Reading it
        // outside meant a hold arriving in between decided which chat the "unavailable" was
        // addressed to — and with the tail cancelled and holds zeroed, that stanza is the last
        // word on presence.
        val target =
            mutex.withLock {
                holds = 0
                idleJob?.cancel()
                idleJob = null
                val wasOnline = online
                online = false
                if (wasOnline) lastChatJid else null
            }
        if (target != null) {
            listener.onWentOffline()
            runCatching { whatsapp.setPresence(target, "unavailable") }
        }
    }

    private suspend fun hold(
        chatJid: String,
        inChat: String?,
    ) {
        val send =
            mutex.withLock {
                lastChatJid = chatJid
                holds += 1
                idleJob?.cancel()
                idleJob = null
                if (online) {
                    false
                } else {
                    online = true
                    true
                }
            }
        if (send) {
            // Before the network call, not after: the state is what the timing model
            // reads, and a failed presence stanza must not leave the two disagreeing.
            listener.onWentOnline(inChat)
            runCatching { whatsapp.setPresence(chatJid, "available") }
        }
    }

    /** Must be called while holding [mutex]. */
    private fun armIdleOff(
        chatJid: String,
        delayMs: Long,
    ): Job =
        scope.launch(CoroutineName("presence-idle-off")) {
            try {
                clock.delay(delayMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            val send =
                mutex.withLock {
                    // A new hold may have arrived while the tail was running.
                    if (holds > 0 || !online) {
                        false
                    } else {
                        online = false
                        idleJob = null
                        true
                    }
                }
            if (send) {
                listener.onWentOffline()
                runCatching { whatsapp.setPresence(chatJid, "unavailable") }
            }
        }

    private fun idleOffMs(): Long =
        IDLE_OFF_MIN_MS + random.nextLong(IDLE_OFF_MAX_MS - IDLE_OFF_MIN_MS + 1)

    companion object {
        /**
         * The tail after the last activity. Seconds, not minutes — this is how long
         * the dot lingers after she stops typing, and with it how long a follow-up
         * message still lands in a chat she is looking at.
         */
        const val IDLE_OFF_MIN_MS = 20_000L
        const val IDLE_OFF_MAX_MS = 40_000L
    }
}
