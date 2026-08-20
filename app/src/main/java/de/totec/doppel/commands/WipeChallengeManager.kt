package de.totec.doppel.commands

import java.security.SecureRandom

fun interface EpochMillisClock {
    fun now(): Long
}

fun interface ConfirmationCodeGenerator {
    fun nextCode(): String
}

data class WipeChallenge(
    val code: String,
    val target: WipeTarget,
    val expiresAt: Long,
)

sealed interface WipeConfirmation {
    data class Confirmed(
        val target: WipeTarget,
    ) : WipeConfirmation

    data object MissingOrExpired : WipeConfirmation
}

/**
 * Short-lived, in-memory confirmation challenges. A challenge is bound to the
 * issuing actor, originating chat and immutable wipe target. A restart safely
 * cancels every pending destructive operation.
 */
class WipeChallengeManager(
    private val clock: EpochMillisClock = EpochMillisClock(System::currentTimeMillis),
    private val codeGenerator: ConfirmationCodeGenerator = secureSixDigitCodeGenerator(),
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private data class Pending(
        val actorId: String,
        val chatId: String,
        val target: WipeTarget,
        val code: String,
        val expiresAt: Long,
    )

    private val pendingByCode = linkedMapOf<String, Pending>()

    init {
        require(ttlMs > 0) { "Challenge TTL must be positive" }
    }

    @Synchronized
    fun issue(actorId: String, chatId: String, target: WipeTarget): WipeChallenge {
        purgeExpired()
        var code: String
        do {
            code = codeGenerator.nextCode()
            require(CODE_PATTERN.matches(code)) { "Confirmation code must have exactly six digits" }
        } while (pendingByCode.containsKey(code))

        // A new request replaces only challenges from the same actor/chat. It
        // cannot overwrite another admin's confirmation.
        pendingByCode.entries.removeAll { (_, value) ->
            value.actorId == actorId && value.chatId == chatId
        }
        val expiresAt = clock.now() + ttlMs
        pendingByCode[code] = Pending(actorId, chatId, target, code, expiresAt)
        return WipeChallenge(code, target, expiresAt)
    }

    @Synchronized
    fun confirm(actorId: String, chatId: String, code: String): WipeConfirmation {
        purgeExpired()
        val pending = pendingByCode[code] ?: return WipeConfirmation.MissingOrExpired
        if (pending.actorId != actorId || pending.chatId != chatId) {
            return WipeConfirmation.MissingOrExpired
        }
        pendingByCode.remove(code)
        return WipeConfirmation.Confirmed(pending.target)
    }

    @Synchronized
    fun pendingCount(): Int {
        purgeExpired()
        return pendingByCode.size
    }

    private fun purgeExpired() {
        val now = clock.now()
        pendingByCode.entries.removeAll { (_, value) -> value.expiresAt <= now }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 2 * 60 * 1000L
        private val CODE_PATTERN = Regex("^\\d{6}$")

        private fun secureSixDigitCodeGenerator(): ConfirmationCodeGenerator {
            val random = SecureRandom()
            return ConfirmationCodeGenerator {
                (100_000 + random.nextInt(900_000)).toString()
            }
        }
    }
}
