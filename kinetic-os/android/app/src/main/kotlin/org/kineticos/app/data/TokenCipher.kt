package org.kineticos.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the server auth token with an AES-256-GCM key that never leaves the Android Keystore (TEE/StrongBox where
 * available). Stored format: "k1:" + base64(iv ‖ ciphertext). If the Keystore is unavailable (e.g. JVM unit tests or
 * broken vendor implementations) the token is kept as "p1:" + plaintext in app-private storage and the UI says so.
 */
object TokenCipher {
    private const val ALIAS = "kinetic-token-key"
    private const val PREFIX_ENC = "k1:"
    private const val PREFIX_PLAIN = "p1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun encrypt(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        PREFIX_ENC + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrElse { PREFIX_PLAIN + plain }

    fun decrypt(stored: String): String? = when {
        stored.startsWith(PREFIX_ENC) -> runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX_ENC), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull() // key lost (e.g. backup restored on another device) → user must log in again
        stored.startsWith(PREFIX_PLAIN) -> stored.removePrefix(PREFIX_PLAIN)
        else -> stored // legacy plaintext from v0.1.0
    }

    fun isHardwareBacked(stored: String?) = stored?.startsWith(PREFIX_ENC) == true
}
