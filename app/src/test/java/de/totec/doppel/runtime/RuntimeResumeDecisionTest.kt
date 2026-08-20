package de.totec.doppel.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResumeDecisionTest {
    @Test
    fun disabledOrStoppedRuntimeNeverResumes() {
        assertFalse(
            RuntimeResumeDecision.shouldResume(
                runRequested = false,
                autoStartEnabled = true,
                latestExitWasUserStop = false,
                latestExitAtMs = 0L,
                lastExplicitStartAtMs = 0L,
            ),
        )
        assertFalse(
            RuntimeResumeDecision.shouldResume(
                runRequested = true,
                autoStartEnabled = false,
                latestExitWasUserStop = false,
                latestExitAtMs = 0L,
                lastExplicitStartAtMs = 0L,
            ),
        )
    }

    @Test
    fun taskManagerStopBlocksAutomaticResurrection() {
        assertFalse(
            RuntimeResumeDecision.shouldResume(
                runRequested = true,
                autoStartEnabled = true,
                latestExitWasUserStop = true,
                latestExitAtMs = 2_000L,
                lastExplicitStartAtMs = 1_000L,
            ),
        )
        assertFalse(
            RuntimeResumeDecision.shouldResume(
                runRequested = true,
                autoStartEnabled = true,
                latestExitWasUserStop = true,
                latestExitAtMs = 1_000L,
                lastExplicitStartAtMs = 1_000L,
            ),
        )
    }

    @Test
    fun newerExplicitStartWinsOverOlderTaskManagerStop() {
        assertTrue(
            RuntimeResumeDecision.shouldResume(
                runRequested = true,
                autoStartEnabled = true,
                latestExitWasUserStop = true,
                latestExitAtMs = 1_000L,
                lastExplicitStartAtMs = 2_000L,
            ),
        )
    }
}
