package de.totec.doppel.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAttemptGateTest {
    @Test
    fun `only one pairing request can run until the active request finishes`() {
        val gate = PairingAttemptGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())

        gate.leave()
        assertTrue(gate.tryEnter())
    }
}
