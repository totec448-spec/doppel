package de.totec.doppel.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The one timer in this app that outlives a suspended CPU.
 *
 * Everything else waits with a coroutine `delay`, which is correct while the wake
 * lock is held and meaningless once it is released — Android's monotonic clock does
 * not tick through deep sleep, so a doze scheduled with `delay` would end after that
 * many *awake* milliseconds, i.e. effectively never on a phone in a pocket. This is
 * therefore the only thing that can end a doze, and [LinkPowerController] arms it
 * before it lets go of the locks.
 *
 * `setAndAllowWhileIdle` rather than the exact variant on purpose: exact alarms need
 * `SCHEDULE_EXACT_ALARM`, which Android grants grudgingly and users can revoke, and
 * the whole schedule this serves is already jittered by minutes to hours. Being woken
 * a few minutes late is invisible here; being unable to arm an alarm at all is fatal.
 * Doze may still hold it to roughly one firing per nine minutes, which is far finer
 * than anything this feature schedules.
 */
internal class LinkWakeAlarm(context: Context) : WakeAlarm {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(AlarmManager::class.java)

    override fun armAt(atMs: Long?): Boolean {
        val alarms = manager ?: return false
        val intent = pendingIntent() ?: return false
        if (atMs == null) {
            alarms.cancel(intent)
            return true
        }
        return runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            true
        }.onFailure {
            // A vendor ROM can refuse this. Nothing here can fix it, but it has to be
            // visible: a refused alarm is the one failure that turns low power mode
            // into a bot that goes to sleep and never comes back.
            Log.w(TAG, "Wake alarm was refused: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * FLAG_IMMUTABLE and an explicit component, like every other PendingIntent here:
     * nothing outside the app may retarget the thing that wakes the bot.
     *
     * A *foreground* service start, even though the service is normally already
     * running: a while-idle alarm firing grants the app a short foreground-start
     * exemption, and this is also the path that has to work when the system killed
     * the process during a doze — which a plain background `startService` is not
     * allowed to do.
     */
    private fun pendingIntent(): PendingIntent? =
        PendingIntent.getForegroundService(
            appContext,
            REQUEST_WAKE,
            Intent(appContext, BridgeForegroundService::class.java)
                .setAction(RuntimeServiceController.ACTION_LINK_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val TAG = "LinkWakeAlarm"

        /** Distinct from the notification actions' request codes. */
        const val REQUEST_WAKE = 4_100
    }
}
