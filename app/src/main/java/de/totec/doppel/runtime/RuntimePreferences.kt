// Lifecycle intent must be committed synchronously and its result propagated.
@file:Suppress("UseKtx")

package de.totec.doppel.runtime

import de.totec.doppel.security.privacySafeErrorType
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Tiny, app-private persistence for lifecycle intent only.
 *
 * No bridge credential, API key, message, JID, prompt, or other secret belongs
 * in this file. The future credential store must use Android Keystore-backed
 * encryption and credential-protected storage.
 */
class RuntimePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    val runRequested: Boolean
        get() = preferences.getBoolean(KEY_RUN_REQUESTED, false)

    val autoStartEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_START, false)

    val lastExplicitStartAtMs: Long
        get() = preferences.getLong(KEY_LAST_EXPLICIT_START_AT, 0L)

    /**
     * Synchronous commit is intentional: START_STICKY or a boot receiver may
     * observe this flag immediately after the current process is terminated.
     */
    fun requestExplicitStart(nowMs: Long = System.currentTimeMillis()): Boolean =
        preferences.edit()
            .putBoolean(KEY_RUN_REQUESTED, true)
            .putLong(KEY_LAST_EXPLICIT_START_AT, nowMs)
            .commit()

    fun requestSystemResume(): Boolean =
        preferences.edit()
            .putBoolean(KEY_RUN_REQUESTED, true)
            .commit()

    /**
     * Persist false before closing the engine, preventing sticky recreation
     * from resurrecting a bot that its user explicitly stopped.
     */
    fun requestStop(): Boolean =
        preferences.edit()
            .putBoolean(KEY_RUN_REQUESTED, false)
            .commit()

    fun setAutoStartEnabled(enabled: Boolean): Boolean =
        preferences.edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .commit()

    private companion object {
        const val FILE_NAME = "whatsapp_bot_runtime_state"
        const val KEY_RUN_REQUESTED = "run_requested"
        const val KEY_AUTO_START = "auto_start_enabled"
        const val KEY_LAST_EXPLICIT_START_AT = "last_explicit_start_at_ms"
    }
}

/**
 * Android 13's task-manager Stop action sends no callback and intentionally
 * leaves scheduled jobs/alarms in place. Compare its recorded exit timestamp
 * with the latest explicit user start so boot/package-replaced handling does
 * not fight a more recent user stop.
 */
internal object RuntimeResumePolicy {
    private const val TAG = "RuntimeResumePolicy"

    fun shouldResumeAfterSystemEvent(
        context: Context,
        preferences: RuntimePreferences,
    ): Boolean {
        if (!preferences.runRequested || !preferences.autoStartEnabled) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true

        return try {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val latestExit = activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()

            RuntimeResumeDecision.shouldResume(
                runRequested = preferences.runRequested,
                autoStartEnabled = preferences.autoStartEnabled,
                latestExitWasUserStop =
                    latestExit?.reason == ApplicationExitInfo.REASON_USER_REQUESTED,
                latestExitAtMs = latestExit?.timestamp ?: 0L,
                lastExplicitStartAtMs = preferences.lastExplicitStartAtMs,
            )
        } catch (error: RuntimeException) {
            // Exit history is defensive protection, not a reason to break a
            // previously explicit and persisted auto-start preference.
            Log.w(
                TAG,
                "Could not inspect historical process exit reason: " +
                    privacySafeErrorType(error),
            )
            true
        }
    }
}

/** Pure decision core kept separate from Android exit-history access. */
internal object RuntimeResumeDecision {
    fun shouldResume(
        runRequested: Boolean,
        autoStartEnabled: Boolean,
        latestExitWasUserStop: Boolean,
        latestExitAtMs: Long,
        lastExplicitStartAtMs: Long,
    ): Boolean {
        if (!runRequested || !autoStartEnabled) return false
        // Equal millisecond timestamps are ambiguous, so the explicit
        // Task-Manager stop wins. Only a strictly newer Start may resume.
        return !latestExitWasUserStop || latestExitAtMs < lastExplicitStartAtMs
    }
}
