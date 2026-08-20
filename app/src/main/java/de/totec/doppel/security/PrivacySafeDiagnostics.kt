package de.totec.doppel.security

/**
 * Returns a diagnostic classification that is safe to persist or send to logcat.
 *
 * Exception messages and stack traces can contain provider bodies, message fragments, JIDs,
 * tokens, or local paths. The concrete type is enough to group failures without copying that
 * private payload into a second storage surface.
 */
internal fun privacySafeErrorType(error: Throwable): String =
    error.javaClass.simpleName
        .takeIf(String::isNotBlank)
        ?.take(120)
        ?: "Throwable"
