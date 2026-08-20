package de.totec.doppel.runtime

import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.transport.BridgeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeSequenceGuardTest {
    @Test
    fun acceptsContiguousFramesAndClassifiesDuplicates() {
        val guard = BridgeSequenceGuard()
        guard.begin(welcome(after = 4, latest = 7, count = 3))
        assertEquals(BridgeSequenceDecision.NEXT, guard.classify(5))
        guard.commit(5)
        assertEquals(BridgeSequenceDecision.DUPLICATE, guard.classify(5))
        assertEquals(BridgeSequenceDecision.NEXT, guard.classify(6))
        guard.commit(6)
        assertEquals(BridgeSequenceDecision.NEXT, guard.classify(7))
        guard.commit(7)
        guard.markReady(7)
    }

    @Test
    fun rejectsMissingCapabilitiesTruncatedReplayAndSequenceGap() {
        assertThrows(IllegalStateException::class.java) {
            BridgeSequenceGuard().begin(
                welcome(after = 4, latest = 7, count = 2),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            BridgeSequenceGuard().begin(
                welcome(after = 0, latest = 0, count = 0, capabilities = emptySet()),
            )
        }
        val guard = BridgeSequenceGuard()
        guard.begin(welcome(after = 4, latest = 7, count = 3))
        assertThrows(IllegalStateException::class.java) { guard.classify(6) }
    }

    @Test
    fun acceptsExplicitlyReconciledCompactionGap() {
        val guard = BridgeSequenceGuard()
        guard.begin(
            welcome(
                after = 99,
                oldest = 100,
                latest = 102,
                count = 3,
                gap = true,
            ),
        )
        assertEquals(BridgeSequenceDecision.NEXT, guard.classify(100))
    }

    /**
     * There is one transport left and it owes the whole contract, so a welcome frame that promises
     * only part of it is rejected rather than admitted on a smaller contract of its own.
     */
    @Test
    fun rejectsAWelcomeThatPromisesOnlyPartOfTheContract() {
        val partial = BridgeSequenceGuard.REQUIRED_CAPABILITIES - "action.send_media"
        assertThrows(IllegalStateException::class.java) {
            BridgeSequenceGuard().begin(
                welcome(after = 4, latest = 7, count = 3, capabilities = partial),
            )
        }
    }

    private fun welcome(
        after: Long,
        oldest: Long = 1,
        latest: Long,
        count: Int,
        gap: Boolean = false,
        capabilities: Set<String> = BridgeSequenceGuard.REQUIRED_CAPABILITIES,
    ) = BridgeFrame.Welcome(
        sequence = latest,
        capabilities = capabilities,
        accountJid = null,
        accountName = null,
        accountState = BridgeConnectionState.CONNECTED,
        resume =
            BridgeFrame.ResumeState(
                requested = after,
                after = after,
                oldest = oldest,
                latest = latest,
                count = count,
                gap = gap,
                reset = false,
            ),
    )
}
