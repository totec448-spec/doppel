package de.totec.doppel.runtime

import de.totec.doppel.security.privacySafeErrorType
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import de.totec.doppel.DoppelApplication
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminContext
import de.totec.doppel.commands.AdminOrigin
import de.totec.doppel.commands.AdminRequest
import de.totec.doppel.commands.AdminResult
import de.totec.doppel.settings.BotSettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Only supported public entry point for app, notification, and future tile UI.
 * Explicit intents plus an unexported service/receiver prevent other apps from
 * changing bot state.
 */
object RuntimeServiceController {
    const val ACTION_START = "de.totec.doppel.runtime.action.START"
    const val ACTION_STOP = "de.totec.doppel.runtime.action.STOP"
    const val ACTION_RECONNECT = "de.totec.doppel.runtime.action.RECONNECT"
    const val ACTION_TOGGLE_RESPONSES =
        "de.totec.doppel.runtime.action.TOGGLE_RESPONSES"

    /**
     * Sent by [LinkWakeAlarm] only. Not a start and not a reconnect: the service is
     * already running and the bridge is already up — this asks the link power loop to
     * look at the clock again now that the device is awake enough to do so.
     */
    const val ACTION_LINK_WAKE = "de.totec.doppel.runtime.action.LINK_WAKE"

    fun start(context: Context) {
        val appContext = context.applicationContext
        check(RuntimePreferences(appContext).requestExplicitStart()) {
            "Android could not persist the requested running state"
        }
        startForegroundService(appContext)
    }

    fun reconnect(context: Context) {
        val appContext = context.applicationContext
        val preferences = RuntimePreferences(appContext)
        if (!preferences.runRequested) {
            check(preferences.requestExplicitStart()) {
                "Android could not persist the requested running state"
            }
            startForegroundService(appContext)
            return
        }

        val intent = serviceIntent(appContext, ACTION_RECONNECT)
        try {
            appContext.startService(intent)
        } catch (error: IllegalStateException) {
            // A notification click is a user-action exemption. Keep this
            // fallback for vendor implementations that still reject startService.
            ContextCompat.startForegroundService(appContext, intent)
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        val persisted = RuntimePreferences(appContext).requestStop()
        val intent = serviceIntent(appContext, ACTION_STOP)

        try {
            // The already-running foreground service receives an orderly stop.
            appContext.startService(intent)
        } catch (error: IllegalStateException) {
            // If no service can receive the command, the durable false flag
            // already prevents sticky/boot resurrection.
            appContext.stopService(intent)
        }
        check(persisted) { "Android could not persist the stopped state" }
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean): Boolean =
        RuntimePreferences(context).setAutoStartEnabled(enabled)

    fun isAutoStartEnabled(context: Context): Boolean =
        RuntimePreferences(context).autoStartEnabled

    internal fun resumeAfterSystemEvent(context: Context) {
        val appContext = context.applicationContext
        if (!RuntimePreferences(appContext).requestSystemResume()) {
            Log.e("RuntimeService", "System resume state could not be persisted")
            return
        }
        startForegroundService(appContext)
    }

    private fun startForegroundService(context: Context) {
        val intent = serviceIntent(context, ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, BridgeForegroundService::class.java).setAction(action)
}

/** Receives only explicit, immutable PendingIntents from our notification. */
class RuntimeControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                RuntimeServiceController.ACTION_START -> RuntimeServiceController.start(context)
                RuntimeServiceController.ACTION_RECONNECT ->
                    RuntimeServiceController.reconnect(context)

                RuntimeServiceController.ACTION_STOP -> RuntimeServiceController.stop(context)
                RuntimeServiceController.ACTION_TOGGLE_RESPONSES -> toggleResponses(context)
            }
        } catch (error: RuntimeException) {
            // A vendor may reject even a user-triggered foreground start. Do
            // not crash the process from a notification action; the durable
            // desired-state flag remains available to the main UI.
            Log.e(TAG, "Runtime control rejected: ${error.javaClass.simpleName}")
        }
    }

    private fun toggleResponses(context: Context) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val application =
                    context.applicationContext as? DoppelApplication
                        ?: error("Application graph is unavailable")
                val currentlyEnabled =
                    application.graph.settings
                        .snapshot()
                        .boolean(BotSettingKeys.ENABLED)
                val actions =
                    application.graph.controls.currentAdminActions()
                        ?: error("Admin controls are unavailable")
                val result =
                    actions.execute(
                        AdminRequest(
                            context =
                                AdminContext(
                                    origin = AdminOrigin.NOTIFICATION,
                                    actorId = "local_notification",
                                    chatId = null,
                                    isGroup = false,
                                ),
                            action =
                                if (currentlyEnabled) {
                                    AdminAction.PauseBot
                                } else {
                                    AdminAction.ResumeBot
                                },
                        ),
                    )
                if (result !is AdminResult.Success) {
                    Log.w(TAG, "Notification response toggle was rejected: ${result::class.simpleName}")
                }
                RuntimeNotificationController(context)
                    .update(RuntimeStateStore.state.value)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Notification response toggle failed: ${error.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RuntimeControlReceiver"
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * Reconciles a persisted user choice after unlock or app replacement.
 *
 * It does no network/database work inside onReceive. remoteMessaging currently
 * remains eligible for BOOT_COMPLETED startup, unlike Android 15 dataSync.
 */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val userManager = context.getSystemService(UserManager::class.java)
        if (!userManager.isUserUnlocked) {
            // Secrets deliberately remain credential-protected. Do not move
            // bridge tokens into device-protected storage for Direct Boot.
            return
        }

        val preferences = RuntimePreferences(context)
        if (!RuntimeResumePolicy.shouldResumeAfterSystemEvent(context, preferences)) return

        try {
            RuntimeServiceController.resumeAfterSystemEvent(context)
        } catch (error: RuntimeException) {
            // Android/vendor policy can still reject a background start. The
            // durable flag remains true for the next explicit app start.
            Log.e(
                TAG,
                "Unable to resume bot after system event: ${privacySafeErrorType(error)}",
            )
        }
    }

    private companion object {
        const val TAG = "RuntimeBootReceiver"
    }
}
