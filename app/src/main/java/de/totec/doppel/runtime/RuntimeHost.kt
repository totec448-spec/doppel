package de.totec.doppel.runtime

import android.content.Context
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow seam between Android process lifecycle and the real bot engine.
 *
 * The Android service never implements bridge, prompt, model, media, command,
 * scheduling, or message behavior. A later composition root installs exactly
 * one factory whose host reuses the canonical BotEngine/BridgeClient pipeline.
 */
fun interface BotRuntimeHostFactory {
    fun create(applicationContext: Context): BotRuntimeHost
}

interface BotRuntimeHost {
    /**
     * Run one connected host session until it ends, fails, or is cancelled.
     * Cancellation is the orderly stop/reconnect signal; implementations must
     * close sockets and child coroutines from a finally block.
     */
    suspend fun run(session: RuntimeHostSession): RuntimeHostResult
}

interface RuntimeHostSession {
    val network: StateFlow<ValidatedNetworkState>

    /**
     * Reports only coarse, non-sensitive state suitable for a lock-screen
     * notification. Do not include message text, identifiers, URLs, or tokens.
     */
    fun report(report: RuntimeHostReport)
}

enum class RuntimeHostPhase {
    CONNECTING,
    ONLINE,
    DEGRADED,
    AUTHENTICATION_REQUIRED,
    ENGINE_UNAVAILABLE,
}

data class RuntimeHostReport(
    val phase: RuntimeHostPhase,
    val detail: String? = null,
)

sealed interface RuntimeHostResult {
    /** A requested persistent session ended normally; reconnect with backoff. */
    data object Completed : RuntimeHostResult

    data class RetryableFailure(val detail: String? = null) : RuntimeHostResult
    data class TerminalFailure(val detail: String) : RuntimeHostResult
}

/**
 * Small process-local dependency registry. Production code should install its
 * factory once from Application.onCreate(), which also covers boot-created
 * processes. Replacing it is useful for instrumentation tests.
 */
object RuntimeDependencies {
    @Volatile
    private var hostFactory: BotRuntimeHostFactory = BotRuntimeHostFactory {
        MissingRuntimeHost
    }

    fun install(factory: BotRuntimeHostFactory) {
        hostFactory = factory
    }

    internal fun createHost(applicationContext: Context): BotRuntimeHost =
        hostFactory.create(applicationContext)
}

/**
 * Safe zero-CPU placeholder until the real composition root is installed.
 * It keeps the lifecycle honest instead of silently inventing a second bot.
 */
private object MissingRuntimeHost : BotRuntimeHost {
    override suspend fun run(session: RuntimeHostSession): RuntimeHostResult {
        session.report(
            RuntimeHostReport(
                phase = RuntimeHostPhase.ENGINE_UNAVAILABLE,
                detail = "The bot engine is not connected yet",
            ),
        )
        awaitCancellation()
    }
}
