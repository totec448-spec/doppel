// Credential writes intentionally fail closed when synchronous commit fails.
@file:Suppress("UseKtx")

package de.totec.doppel.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only encrypted blobs in SharedPreferences. The wrapping key never
 * leaves Android Keystore and intentionally has no biometric requirement:
 * unattended foreground processing must be able to use the bridge and model
 * credentials after the user has started the bot.
 *
 * Auto Backup is disabled in the manifest. Restoring ciphertext without the
 * device-bound Keystore key would otherwise make credentials undecryptable.
 */
class SecretStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun put(name: String, value: String) {
        require(name in ALLOWED_NAMES) { "Unsupported secret name" }
        if (value.isEmpty()) {
            remove(name)
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed =
            "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(name, packed).commit()) {
            "Could not persist encrypted credential"
        }
    }

    @Synchronized
    fun get(name: String): String? {
        require(name in ALLOWED_NAMES) { "Unsupported secret name" }
        val packed = preferences.getString(name, null) ?: return null
        val separator = packed.indexOf('.')
        if (separator <= 0 || separator == packed.lastIndex) {
            throw SecretUnavailableException("Stored credential has an invalid envelope")
        }

        return try {
            val iv = Base64.decode(packed.substring(0, separator), Base64.NO_WRAP)
            val encrypted = Base64.decode(packed.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (error: AEADBadTagException) {
            throw SecretUnavailableException(
                "Credential cannot be decrypted on this device; enter it again",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw SecretUnavailableException("Stored credential is corrupt", error)
        }
    }

    @Synchronized
    fun has(name: String): Boolean = preferences.contains(name)

    /** True only when ciphertext exists and can still be decrypted with this device's key. */
    @Synchronized
    fun isReadable(name: String): Boolean =
        runCatching { !get(name).isNullOrBlank() }.getOrDefault(false)

    @Synchronized
    fun remove(name: String) {
        check(preferences.edit().remove(name).commit()) {
            "Could not remove encrypted credential"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val OPENROUTER_API_KEY = "openrouter_api_key"
        const val BRIDGE_TOKEN = "bridge_token"
        const val STT_API_KEY = "stt_api_key"

        private val ALLOWED_NAMES =
            setOf(OPENROUTER_API_KEY, BRIDGE_TOKEN, STT_API_KEY)
        private const val PREFERENCES_NAME = "encrypted_credentials_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "whatsapp_bot_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}

class SecretUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
