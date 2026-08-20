package de.totec.doppel.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNetworkPolicyTest {
    @Test
    fun sameValidatedDefaultNetworkKeepsSession() {
        assertFalse(
            RuntimeNetworkPolicy.shouldRestartSession(
                initialNetworkHandle = 42L,
                isValidated = true,
                currentNetworkHandle = 42L,
            ),
        )
    }

    @Test
    fun lossOfValidationRestartsSession() {
        assertTrue(
            RuntimeNetworkPolicy.shouldRestartSession(
                initialNetworkHandle = 42L,
                isValidated = false,
                currentNetworkHandle = 42L,
            ),
        )
    }

    @Test
    fun validatedDefaultNetworkHandoverRestartsSession() {
        assertTrue(
            RuntimeNetworkPolicy.shouldRestartSession(
                initialNetworkHandle = 42L,
                isValidated = true,
                currentNetworkHandle = 84L,
            ),
        )
    }
}
