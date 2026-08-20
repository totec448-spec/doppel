package de.totec.doppel.integration

import de.totec.doppel.data.db.OUTBOUND_KIND_TURN_PERMIT
import de.totec.doppel.data.db.OutboundDecision
import de.totec.doppel.data.db.OutboundSafetyRecord
import de.totec.doppel.data.db.OutboundStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundRecoverySafetyTest {
    private val visibleReservation =
        OutboundSafetyRecord(
            dedupeKey = "visible",
            outboundKind = "reply",
            decision = OutboundDecision.ALLOW,
            reasonCode = "policy_allow",
            plannedAt = 200,
        )

    @Test
    fun `turn permit alone never blocks recovery before a visible send`() {
        assertFalse(
            visibleReservation
                .copy(outboundKind = OUTBOUND_KIND_TURN_PERMIT)
                .blocksInboundRecoveryAfter(100),
        )
        assertTrue(visibleReservation.blocksInboundRecoveryAfter(100))
        assertFalse(visibleReservation.copy(plannedAt = 99).blocksInboundRecoveryAfter(100))
        assertFalse(
            visibleReservation
                .copy(status = OutboundStatus.FAILED)
                .blocksInboundRecoveryAfter(100),
        )
    }
}
