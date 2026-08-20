package de.totec.doppel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The X on an error row has to mean something. The provider fails on every retry of the same turn,
 * so an alert channel that simply re-published would put the row straight back and make the button
 * look broken.
 */
class AppControlRegistryAlertsTest {
    private fun alert(
        kind: AlertKind,
        title: String,
    ) = RuntimeAlert(kind = kind, title = title, detail = "detail")

    @Test
    fun `one alert per kind, newest wins`() {
        val registry = AppControlRegistry()
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 429"))
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 500"))
        assertEquals(1, registry.alerts.value.size)
        assertEquals("OpenRouter 500", registry.alerts.value.single().title)
    }

    @Test
    fun `different kinds stack`() {
        val registry = AppControlRegistry()
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 500"))
        registry.publishAlert(alert(AlertKind.API_KEY, "No OpenRouter API key"))
        assertEquals(2, registry.alerts.value.size)
    }

    @Test
    fun `a dismissed alert stays dismissed while it says the same thing`() {
        val registry = AppControlRegistry()
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 429"))
        registry.dismissAlert(AlertKind.PROVIDER)
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 429"))
        assertTrue(registry.alerts.value.isEmpty())
    }

    /** A different status is a different problem, and the owner has not seen this one. */
    @Test
    fun `a different problem shows again`() {
        val registry = AppControlRegistry()
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 429"))
        registry.dismissAlert(AlertKind.PROVIDER)
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 402"))
        assertEquals("OpenRouter 402", registry.alerts.value.single().title)
    }

    /** Recovery re-arms the row: after a call that worked, the same failure is news again. */
    @Test
    fun `clearing re-arms a dismissed alert`() {
        val registry = AppControlRegistry()
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 429"))
        registry.dismissAlert(AlertKind.PROVIDER)
        registry.clearAlert(AlertKind.PROVIDER)
        registry.publishAlert(alert(AlertKind.PROVIDER, "OpenRouter 429"))
        assertEquals("OpenRouter 429", registry.alerts.value.single().title)
    }
}
