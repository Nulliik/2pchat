package com.example.twopchat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts identity material with a non-exportable Android Keystore key. */
object IdentityKeyStore {
    private const val ALIAS = "2pchat_identity_storage_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
            cachedKey = key
            key
        }
    }

    @JvmStatic
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plainBytes)
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        val result = Base64.encodeToString(payload, Base64.NO_WRAP)
        com.example.twopchat.SecurityUtils.zeroize(plainBytes)
        com.example.twopchat.SecurityUtils.zeroize(ciphertext)
        com.example.twopchat.SecurityUtils.zeroize(payload)
        return result
    }

    @JvmStatic
    fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.isNotEmpty()) { "Empty encrypted identity" }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 12..16 && payload.size > 1 + ivSize) { "Invalid encrypted identity" }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val ciphertext = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        val plainBytes = cipher.doFinal(ciphertext)
        val result = plainBytes.toString(Charsets.UTF_8)
        com.example.twopchat.SecurityUtils.zeroize(payload)
        com.example.twopchat.SecurityUtils.zeroize(iv)
        com.example.twopchat.SecurityUtils.zeroize(ciphertext)
        com.example.twopchat.SecurityUtils.zeroize(plainBytes)
        return result
    }

    @JvmStatic
    @Synchronized
    fun deleteKey() {
        cachedKey = null
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(ALIAS)) deleteEntry(ALIAS)
        }
    }
}
