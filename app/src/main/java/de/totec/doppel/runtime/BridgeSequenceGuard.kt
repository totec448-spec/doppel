package de.totec.doppel.runtime

import de.totec.doppel.transport.BridgeFrame

internal enum class BridgeSequenceDecision {
    NEXT,
    DUPLICATE,
}

/**
 * Enforces one authenticated handshake and a strictly contiguous event stream.
 * A transport frame is committed only after its Android-side effect has
 * completed and the bridge acknowledgement has been accepted.
 */
internal class BridgeSequenceGuard {
    private var initialized = false
    private var committedSequence = 0L
    private var pendingSequence: Long? = null

    /**
     * There is one transport and it owes the whole contract.
     *
     * The capability set used to be a parameter, because a second transport promised only part of
     * it. That transport is gone, and with it the question of which contract a welcome frame is
     * being measured against.
     */
    fun begin(welcome: BridgeFrame.Welcome) {
        check(!initialized) { "Duplicate bridge welcome" }
        check(REQUIRED_CAPABILITIES.all(welcome.capabilities::contains)) {
            "Bridge is missing required durable capabilities"
        }
        val resume = welcome.resume
        check(welcome.sequence == resume.latest) { "Bridge journal bounds do not match welcome" }
        check(resume.after <= resume.latest) { "Bridge resume cursor exceeds journal" }
        check(resume.count.toLong() == resume.latest - resume.after) {
            "Bridge replay is not contiguous"
        }
        if (resume.count > 0) {
            check(resume.after + 1 >= resume.oldest) { "Bridge replay starts before retained journal" }
        }
        if (resume.gap) {
            check(resume.after == (resume.oldest - 1).coerceAtLeast(0)) {
                "Bridge gap cursor was not reconciled"
            }
        }
        committedSequence = resume.after
        initialized = true
    }

    fun classify(sequence: Long): BridgeSequenceDecision {
        check(initialized) { "Bridge event arrived before welcome" }
        check(sequence >= 0) { "Bridge sequence is invalid" }
        if (sequence <= committedSequence) return BridgeSequenceDecision.DUPLICATE
        check(pendingSequence == null) { "Bridge event processing overlapped" }
        check(sequence == committedSequence + 1) {
            "Bridge sequence gap: expected ${committedSequence + 1}, received $sequence"
        }
        pendingSequence = sequence
        return BridgeSequenceDecision.NEXT
    }

    fun commit(sequence: Long) {
        check(pendingSequence == sequence) { "Bridge sequence commit is out of order" }
        committedSequence = sequence
        pendingSequence = null
    }

    /**
     * Gives a frame back after its Android-side effect failed, so the very same frame can be
     * attempted again.
     *
     * Without this a retry runs into "Bridge event processing overlapped" — the guard would still
     * be holding the half-processed predecessor, and the second attempt would report that instead
     * of the fault that actually happened.
     */
    fun abandon(sequence: Long) {
        if (pendingSequence == sequence) pendingSequence = null
    }

    /**
     * Steps over an event that could not be processed after repeated attempts.
     *
     * The stream has to stay contiguous, so a skipped event is committed rather than left pending;
     * the caller is responsible for making the skip visible in the operator log.
     */
    fun quarantine(sequence: Long) {
        check(initialized) { "Bridge event arrived before welcome" }
        if (sequence > committedSequence) committedSequence = sequence
        pendingSequence = null
    }

    fun markReady(sequence: Long) {
        check(initialized) { "Bridge became ready before welcome" }
        check(pendingSequence == null) { "Bridge became ready during event processing" }
        check(sequence == committedSequence) { "Bridge ready cursor does not match replay" }
    }

    companion object {
        val REQUIRED_CAPABILITIES =
            setOf(
                "protocol.v1",
                "resume.sequence",
                "replay.ready",
                "journal.durable",
                "idempotency.durable",
                "media.http-stream",
                "event.connection",
                "event.incoming",
                "event.delivery",
                "event.safety",
                "action.pair",
                "action.reconnect",
                "action.send_text",
                "action.send_media",
                "action.send_reaction",
                "action.mark_read",
                "action.mark_played",
                "action.presence",
                "action.set_ingress_policy",
                "action.edit_message",
                "action.block",
                "action.unblock",
            )
    }
}
