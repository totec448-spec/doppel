package de.totec.doppel.transport

import android.content.Context
import nativewa.Nativewa
import nativewa.Runtime

/**
 * Owns the Go/whatsmeow runtime that is compiled into this APK.
 *
 * The compatibility server only listens on Android loopback, retains the existing versioned bridge
 * contract, and — deliberately — **outlives a single bridge session**.
 *
 * It used to be closed together with the Kotlin host, which meant every retryable session end tore
 * down the linked device itself: noise handshake, app-state resync, offline replay, the joined-group
 * IQ and the whole safety IQ round, all over again. Most of those session ends are purely local
 * (the loopback WebSocket, a repository write, a frame that could not be decoded) and have nothing
 * to say about the WhatsApp link. Reconnecting the loopback socket to a runtime that never went away
 * is what the durable journal and its resume cursor were built for.
 *
 * A genuinely broken native core is still recoverable: the foreground service escalates to
 * [shutdown] after repeated failures and before every terminal verdict, and the next [acquire]
 * starts a fresh one.
 */
class EmbeddedNativeBridge private constructor(
    private val runtime: Runtime,
    private val token: String,
) {
    val baseUrl: String
        get() = runtime.baseURL()

    companion object {
        const val LOOPBACK_PORT = 18_787
        private val RUNTIME_LOCK = Any()
        private var active: EmbeddedNativeBridge? = null

        /**
         * The process-global native core, started on first use and reused afterwards.
         *
         * Reuse is keyed on the bridge token: a rotated token means the running server would refuse
         * the new session's bearer, so that one runtime is replaced rather than reused.
         */
        fun acquire(
            context: Context,
            token: String,
        ): EmbeddedNativeBridge {
            synchronized(RUNTIME_LOCK) {
                active?.let { existing ->
                    if (existing.token == token) return existing
                    stopLocked()
                }
                val appContext = context.applicationContext
                val root = appContext.getDir("native-wa", Context.MODE_PRIVATE)
                val media = appContext.getDir("native-wa-media", Context.MODE_PRIVATE)
                val runtime =
                    Nativewa.start(
                        root.resolve("whatsapp.db").absolutePath,
                        media.absolutePath,
                        token,
                        LOOPBACK_PORT.toLong(),
                    )
                val created = EmbeddedNativeBridge(runtime, token)
                if (!created.baseUrl.startsWith("http://127.0.0.1:")) {
                    runtime.stop()
                    error("Native WhatsApp bridge did not bind to Android loopback")
                }
                active = created
                return created
            }
        }

        /**
         * Stops the native core for real.
         *
         * Idempotent, and the only path that ends the linked-device connection: an orderly service
         * stop, a terminal verdict, or the escalation after repeated session failures.
         */
        fun shutdown() {
            synchronized(RUNTIME_LOCK) { stopLocked() }
        }

        /** Must be called while holding [RUNTIME_LOCK]. */
        private fun stopLocked() {
            active?.runtime?.stop()
            active = null
        }

    }
}
