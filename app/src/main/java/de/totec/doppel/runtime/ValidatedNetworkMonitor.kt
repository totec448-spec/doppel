package de.totec.doppel.runtime

import de.totec.doppel.security.privacySafeErrorType
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ValidatedNetworkState(
    val isValidated: Boolean = false,
    val isMetered: Boolean = true,
    val networkHandle: Long? = null,
)

/**
 * Callback-driven default-network monitor. It never polls connectivity.
 *
 * A foreground process does not make an unvalidated/captive network usable.
 * The runtime waits for INTERNET + VALIDATED and lets Android choose the
 * current default network, including VPN transitions.
 */
internal class ValidatedNetworkMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lifecycleLock = Any()
    private val mutableState = MutableStateFlow(ValidatedNetworkState())

    @Volatile
    private var registered = false

    val state: StateFlow<ValidatedNetworkState> = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            publish(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            if (!registered) return
            if (mutableState.value.networkHandle == network.networkHandle) {
                mutableState.value = ValidatedNetworkState()
            }
        }
    }

    fun start(): Boolean =
        synchronized(lifecycleLock) {
            if (registered) return@synchronized true

            try {
                // Mark first while holding the lifecycle lock so a callback
                // delivered during registration is accepted. stop() cannot
                // race past registration and leak the callback.
                registered = true
                connectivityManager.registerDefaultNetworkCallback(callback)
                true
            } catch (error: RuntimeException) {
                registered = false
                mutableState.value = ValidatedNetworkState()
                Log.e(
                    TAG,
                    "Unable to register default network callback: " +
                        privacySafeErrorType(error),
                )
                false
            }
        }

    fun stop() {
        synchronized(lifecycleLock) {
            if (!registered) {
                mutableState.value = ValidatedNetworkState()
                return
            }

            registered = false
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (error: RuntimeException) {
                // unregister can race process/service teardown. There is no
                // useful recovery action once this monitor is already stopping.
                Log.w(
                    TAG,
                    "Unable to unregister default network callback: " +
                        privacySafeErrorType(error),
                )
            } finally {
                mutableState.value = ValidatedNetworkState()
            }
        }
    }

    private fun publish(network: Network, capabilities: NetworkCapabilities) {
        if (!registered) return

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        mutableState.value = ValidatedNetworkState(
            isValidated = hasInternet && isValidated,
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            networkHandle = network.networkHandle,
        )
    }

    private companion object {
        const val TAG = "ValidatedNetwork"
    }
}
