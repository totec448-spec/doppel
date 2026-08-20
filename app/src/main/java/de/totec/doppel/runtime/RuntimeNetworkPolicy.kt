package de.totec.doppel.runtime

/** Pure default-network handover rule, separated for JVM coverage. */
internal object RuntimeNetworkPolicy {
    fun shouldRestartSession(
        initialNetworkHandle: Long?,
        isValidated: Boolean,
        currentNetworkHandle: Long?,
    ): Boolean =
        !isValidated ||
            (
                initialNetworkHandle != null &&
                    currentNetworkHandle != initialNetworkHandle
            )
}
