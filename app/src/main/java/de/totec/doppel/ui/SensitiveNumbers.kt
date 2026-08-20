package de.totec.doppel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the screen is currently hiding phone numbers, fed by the `hide_sensitive_data` setting.
 *
 * The switch is one value, but the numbers it covers are painted by leaf rows in five files, most
 * of them several composables below the screen that holds the state. Threading a display-only flag
 * through all of those signatures would put privacy into APIs that have nothing to do with it, so
 * it is provided once at the top of the app instead. Static: it changes about as often as the
 * owner opens the setting, and every reader repaints when it does.
 */
val LocalHideNumbers = staticCompositionLocalOf { false }

/**
 * A phone number or JID with everything that identifies its owner removed.
 *
 * Only the dialling prefix survives — enough to tell a German contact from a Dutch one in a
 * screenshot, never enough to reach anybody. Anything that is not a dialled number comes back
 * untouched: a name is not a number, and putting a name where a masked number was would hand over
 * exactly the identity the mask exists to hide, so the callers pick the number over the name while
 * hiding is on rather than letting this function guess.
 *
 * Masking is a rendering step and only a rendering step. No stored value, no log line and nothing
 * the bot sends is ever the masked form.
 */
fun maskNumber(value: String): String {
    val trimmed = value.trim()
    val head = trimmed.substringBefore('@')
    if (!DIALLED_NUMBER.matches(head)) return trimmed
    val digits = head.filter(Char::isDigit)
    if (digits.length <= VISIBLE_DIGITS) return trimmed
    val plus = if (head.startsWith('+')) "+" else ""
    return plus + digits.take(VISIBLE_DIGITS) + "…"
}

/** [maskNumber], but only while [LocalHideNumbers] is on. */
@Composable
fun masked(value: String): String = if (LocalHideNumbers.current) maskNumber(value) else value

/**
 * Digits left standing: a country code and the network prefix behind it. Fewer would make every
 * contact look alike, more starts identifying the subscriber.
 */
private const val VISIBLE_DIGITS = 5

/**
 * A value that is dialled rather than read. A group called `Group 704810` or a contact called
 * `Anna` is not one of these and stays as it is.
 */
private val DIALLED_NUMBER = Regex("""\+?[0-9][0-9 ()./-]*""")
