package de.totec.doppel.transport

import java.util.Base64

/**
 * Exact native counterpart of the companion CONTROL_TOKEN contract.
 * Human passwords are rejected: accepted values encode at least 256 random
 * bits as 64+ hexadecimal or 43+ unpadded base64url characters.
 */
object BridgeControlToken {
    private val hex = Regex("^[a-fA-F0-9]+$")
    private val base64Url = Regex("^[A-Za-z0-9_-]+$")

    fun isValid(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        val decoded =
            if (value.length % 2 == 0 && hex.matches(value)) {
                if (value.length < 64) return false
                runCatching {
                    ByteArray(value.length / 2) { index ->
                        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                    }
                }.getOrNull()
            } else {
                if (value.length < 43 || !base64Url.matches(value)) return false
                runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
            }
        return decoded != null && decoded.size >= 32
    }
}
