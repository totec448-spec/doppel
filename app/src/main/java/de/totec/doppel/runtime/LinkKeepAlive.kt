package de.totec.doppel.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * A single OS lock, behind an interface so the bookkeeping below stays testable
 * without an Android runtime.
 */
internal interface HeldLock {
    fun acquire()

    fun release()
}

/**
 * Keeps the CPU and the Wi-Fi radio awake while the linked-device socket is
 * supposed to be up.
 *
 * Measured on the deployment device (Xiaomi/HyperOS, Wi-Fi, app on the deviceidle
 * whitelist and in standby bucket 5): with the screen off and Android's battery
 * saver active, the whatsmeow websocket died with
 * `failed to read frame header: EOF` at an almost exact 60 s cadence — 54 drops
 * per hour, hour after hour. The drops started the minute battery saver switched
 * on (11:04) and stopped the minute it switched off (13:47); the Wi-Fi driver
 * logged `KeepAliveTimer: send ignore, screen is off` throughout. The socket
 * itself was healthy: the same process ran for hours without a single drop while
 * the screen was on, on the same unchanged Wi-Fi network.
 *
 * The cause is suspend, not the bridge: a suspended CPU cannot run whatsmeow's
 * keepalive goroutine, and a radio in power save drops the connection the server
 * then reports as closed. A foreground service does not prevent suspend — only a
 * wake lock does. The service comment used to argue against a permanent wake
 * lock; that was right for a service that merely wants to survive, and wrong for
 * one whose entire purpose is an always-on socket. The lock is held exactly as
 * long as the user asked the bot to run, and released on every stop.
 */
internal class LinkKeepAlive(
    private val locks: List<HeldLock>,
    /**
     * Injected so the bookkeeping stays a plain JVM unit test; `android.util.Log`
     * is not available there. Only the class name travels — a vendor lock failure
     * message can carry device identifiers.
     */
    private val onLockFailure: (String, Throwable) -> Unit = { stage, error ->
        Log.w(TAG, "Keepalive $stage failed: ${error.javaClass.simpleName}")
    },
) {
    private var held = false

    @Synchronized
    fun acquire() {
        if (held) return
        held = true
        locks.forEach { lock ->
            runCatching { lock.acquire() }.onFailure { onLockFailure("acquire", it) }
        }
    }

    @Synchronized
    fun release() {
        if (!held) return
        held = false
        locks.forEach { lock ->
            runCatching { lock.release() }.onFailure { onLockFailure("release", it) }
        }
    }

    @get:Synchronized
    val isHeld: Boolean
        get() = held

    companion object {
        private const val TAG = "LinkKeepAlive"

        /** Tags are prefixed with the package so they are attributable in `dumpsys power`. */
        private const val WAKE_LOCK_TAG = "de.totec.doppel:linked-device"
        private const val WIFI_LOCK_TAG = "de.totec.doppel:linked-device"

        fun forService(context: Context): LinkKeepAlive {
            val appContext = context.applicationContext
            val locks = buildList {
                partialWakeLock(appContext)?.let(::add)
                wifiLock(appContext)?.let(::add)
            }
            return LinkKeepAlive(locks)
        }

        private fun partialWakeLock(context: Context): HeldLock? {
            val power = context.getSystemService(PowerManager::class.java) ?: return null
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                // Reference counting would have to mirror the service lifecycle exactly;
                // the flat held flag above is the single source of truth instead.
                setReferenceCounted(false)
            }
            return object : HeldLock {
                override fun acquire() = lock.acquire()

                override fun release() = lock.release()
            }
        }

        /**
         * Best effort. `WIFI_MODE_FULL_HIGH_PERF` is deprecated since API 29 in favour of
         * `WIFI_MODE_FULL_LOW_LATENCY`, and neither is guaranteed to be honoured by a vendor
         * driver — this device's `dumpsys wifi` shows low latency is the mode actually used.
         * The wake lock does the load-bearing work; this only asks the radio to stay out of
         * power save on top of it.
         */
        private fun wifiLock(context: Context): HeldLock? {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return null
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val lock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
            }
            return object : HeldLock {
                override fun acquire() = lock.acquire()

                override fun release() = lock.release()
            }
        }
    }
}

/**
 * Read-only view of the device power settings that decide whether an always-on
 * socket survives a screen-off period. Reported into the activity log so a link
 * that keeps flapping names its cause instead of looking like a bridge bug.
 */
object DevicePowerState {
    fun powerSaveActive(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }.getOrDefault(false)

    fun batteryOptimizationExempt(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

    /** Null when nothing is worth telling the user about. */
    fun warningOrNull(context: Context): String? {
        val saver = powerSaveActive(context)
        val exempt = batteryOptimizationExempt(context)
        return when {
            saver && !exempt ->
                "Battery saver is on and the app is not exempt from battery optimisation. " +
                    "The WhatsApp connection drops while the screen is off."
            saver ->
                "Battery saver is on. The WhatsApp connection can drop while the screen is " +
                    "off; charge the device or turn battery saver off."
            !exempt ->
                "The app is not exempt from battery optimisation. Android may cut the " +
                    "WhatsApp connection while the screen is off."
            else -> null
        }
    }
}
