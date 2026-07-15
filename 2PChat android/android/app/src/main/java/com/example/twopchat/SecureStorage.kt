package com.example.twopchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small envelope-encryption primitive backed by a non-exportable Android Keystore key. */
object SecureStorage {
    private const val KEY_ALIAS = "2pchat_local_storage_v1"
    private const val PREFIX = "enc:v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
            }
            cachedKey = key
            key
        }
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /** Returns legacy plaintext unchanged, enabling non-destructive migration. */
    fun decrypt(value: String?): String? {
        if (value == null || !value.startsWith(PREFIX)) return value
        val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        require(packed.size > 12) { "Invalid encrypted value" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, 12))
        return String(cipher.doFinal(packed, 12, packed.size - 12), Charsets.UTF_8)
    }

    fun isEncrypted(value: String?) = value?.startsWith(PREFIX) == true

    /** Binary envelope used for private media which must not be left as plaintext files. */
    fun encryptBytes(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(BINARY_VERSION) + cipher.iv + cipher.doFinal(value)
    }

    fun decryptBytes(value: ByteArray): ByteArray {
        require(value.size > 13 && value[0] == BINARY_VERSION) { "Invalid encrypted binary value" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value, 1, 12))
        return cipher.doFinal(value, 13, value.size - 13)
    }

    private var dbPassphraseMem: String? = null

    @Synchronized
    fun getOrGenerateDbPassphrase(context: android.content.Context): String {
        dbPassphraseMem?.let { return it }
        val sharedPrefs = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
        val enc = sharedPrefs.getString("db_passphrase_enc", null)
        if (enc != null) {
            val dec = decrypt(enc)
            if (dec != null) {
                dbPassphraseMem = dec
                return dec
            }
        }
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val pass = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val encrypted = encrypt(pass)
        sharedPrefs.edit().putString("db_passphrase_enc", encrypted).apply()
        dbPassphraseMem = pass
        return pass
    }

    @Synchronized
    fun clearDbPassphrase() {
        dbPassphraseMem = null
    }

    private const val BINARY_VERSION: Byte = 1
}
