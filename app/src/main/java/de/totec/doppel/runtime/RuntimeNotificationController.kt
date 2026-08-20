package de.totec.doppel.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Low-noise status and controls for the user-perceptible bot session.
 *
 * Notifications are updated only on coarse lifecycle changes. Heartbeats and
 * individual messages must never produce notification churn.
 */
internal class RuntimeNotificationController(context: Context) {
    private val applicationContext = context.applicationContext
    private val notificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)
    private var lastRendered: Pair<String, String>? = null

    /**
     * What the link power model says, while it says anything.
     *
     * It has to override the phase line rather than sit beside it: from the runtime's
     * point of view a sleeping link is an unexplained dropped socket, and the
     * notification would read "Reconnecting to WhatsApp" all night.
     */
    private var sleepNote: String? = null

    fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bot background service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status and controls for the running WhatsApp bot"
                setShowBadge(false)
            },
        )
    }

    fun buildForeground(state: RuntimeState): Notification {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(de.totec.doppel.R.drawable.ic_bot_status)
            .setContentTitle(titleFor(state.phase))
            .setContentText(detailFor(state))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Replies on/off",
                controlPendingIntent(
                    action = RuntimeServiceController.ACTION_TOGGLE_RESPONSES,
                    requestCode = REQUEST_TOGGLE_RESPONSES,
                ),
            )
            .addAction(
                android.R.drawable.ic_popup_sync,
                "Reconnect",
                controlPendingIntent(
                    action = RuntimeServiceController.ACTION_RECONNECT,
                    requestCode = REQUEST_RECONNECT,
                ),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                controlPendingIntent(
                    action = RuntimeServiceController.ACTION_STOP,
                    requestCode = REQUEST_STOP,
                ),
            )

        launchPendingIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    /** Null puts the ordinary phase line back. Redraws only when the text actually changes. */
    fun setSleepNote(note: String?, state: RuntimeState) {
        if (sleepNote == note) return
        sleepNote = note
        update(state)
    }

    fun update(state: RuntimeState) {
        // Only the title and the detail line are visible. Runtime state changes far more often
        // than either of them, and every notify() is a binder round trip plus a system-UI redraw.
        val rendered = titleFor(state.phase) to detailFor(state)
        if (rendered == lastRendered) return
        lastRendered = rendered
        notificationManager.notify(NOTIFICATION_ID, buildForeground(state))
    }

    fun cancel() {
        lastRendered = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun launchPendingIntent(): PendingIntent? {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null

        return PendingIntent.getActivity(
            applicationContext,
            REQUEST_OPEN,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun controlPendingIntent(action: String, requestCode: Int): PendingIntent {
        val explicitIntent = Intent(applicationContext, RuntimeControlReceiver::class.java)
            .setAction(action)

        return PendingIntent.getBroadcast(
            applicationContext,
            requestCode,
            explicitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun titleFor(phase: RuntimePhase): String =
        when {
            // A stop or a real failure still wins: those are not sleep, and the sleep
            // note may simply not have been cleared yet on the way down.
            sleepNote != null &&
                phase != RuntimePhase.STOPPING &&
                phase != RuntimePhase.STOPPED &&
                phase != RuntimePhase.ATTENTION_REQUIRED &&
                phase != RuntimePhase.ERROR -> "Doppel is asleep"

            else -> titleForPhase(phase)
        }

    private fun titleForPhase(phase: RuntimePhase): String =
        when (phase) {
            RuntimePhase.ONLINE -> RuntimeText.TITLE_RUNNING
            RuntimePhase.STOPPING, RuntimePhase.STOPPED -> "Doppel is shutting down"
            RuntimePhase.ATTENTION_REQUIRED,
            RuntimePhase.ENGINE_UNAVAILABLE,
            RuntimePhase.ERROR,
            -> RuntimeText.TITLE_ATTENTION

            else -> "Doppel is connecting"
        }

    private fun detailFor(state: RuntimeState): String =
        when (state.phase) {
            RuntimePhase.STOPPING,
            RuntimePhase.STOPPED,
            RuntimePhase.ATTENTION_REQUIRED,
            RuntimePhase.ERROR,
            -> state.detail ?: "Background service is running"

            RuntimePhase.BACKING_OFF -> {
                val seconds = (state.retryDelayMs + 999L) / 1_000L
                "${state.detail ?: "Retrying"} in ${seconds}s"
            }

            else -> sleepNote ?: state.detail ?: "Background service is running"
        }

    companion object {
        const val CHANNEL_ID = "bot_runtime"
        const val NOTIFICATION_ID = 41_001

        private const val REQUEST_OPEN = 41_010
        private const val REQUEST_RECONNECT = 41_011
        private const val REQUEST_STOP = 41_012
        private const val REQUEST_TOGGLE_RESPONSES = 41_013
    }
}
