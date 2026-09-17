package com.example.twopchat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.twopchat.config.P2PPreferences
import android.util.Base64
import android.util.LruCache
import com.example.twopchat.logging.SafeLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small envelope-encryption primitive backed by a non-exportable Android Keystore key. */
object SecureStorage : SensitiveMemoryHolder {
    init {
        SensitiveMemoryRegistry.register(this)
    }

    override fun clearSensitiveMemory() {
        clearDbPassphrase()
    }

    private const val KEY_ALIAS = "2pchat_local_storage_v1"
    private const val PREFIX = "enc:v1:"
    private val stringDecryptionCache = LruCache<String, String>(1024)

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var cachedDbPassphrase: ByteArray? = null

    /** Asynchronously pre-warms the Keystore key and DB passphrase on background thread. */
    fun prewarm(context: android.content.Context) {
        try {
            val passphrase = getOrGenerateDbPassphrase(context)
            try {
                key()
            } finally {
                SecurityUtils.zeroize(passphrase)
            }
        } catch (e: Exception) {
            SafeLog.w("SecureStorage", "Failed to prewarm keystore", e)
        }
    }

    private fun createCipher(): Cipher {
        return Cipher.getInstance("AES/GCM/NoPadding")
    }

    private fun key(allowCreate: Boolean = true): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                SafeLog.w("SecureStorage", "Keystore key() requested synchronously on Main Thread before prewarm completed!")
            }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }

            check(allowCreate && !store.containsAlias(KEY_ALIAS)) { "Storage keystore key unavailable" }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            var key: SecretKey? = null

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    val strongBoxSpec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    ).setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setIsStrongBoxBacked(true)
                        .build()
                    keyGenerator.init(strongBoxSpec)
                    key = keyGenerator.generateKey()
                } catch (_: Exception) {
                    // StrongBox is not present on device hardware; fallback to TEE KeyStore
                    key = null
                }
            }

            if (key == null) {
                val teeSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(teeSpec)
                key = keyGenerator.generateKey()
            }

            cachedKey = key
            key
        }
    }

    internal class StringCipher internal constructor(
        private val secretKey: SecretKey,
        private val cipher: Cipher,
    ) {
        fun encrypt(value: String): String {
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            return try {
                encryptEnvelope(valueBytes, secretKey, cipher)
            } finally {
                SecurityUtils.zeroize(valueBytes)
            }
        }

        fun decrypt(value: String?): String? {
            if (value == null || !isEncrypted(value)) return value
            val plainBytes = decryptEnvelope(value, secretKey, cipher)
            return try {
                String(plainBytes, Charsets.UTF_8)
            } finally {
                SecurityUtils.zeroize(plainBytes)
            }
        }
    }

    private fun encryptEnvelope(value: ByteArray, secretKey: SecretKey = key(), cipher: Cipher = createCipher()): String {
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val cipherBytes = cipher.doFinal(value)
        try {
            val packed = cipher.iv + cipherBytes
            return try {
                PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
            } finally {
                SecurityUtils.zeroize(packed)
            }
        } finally {
            SecurityUtils.zeroize(cipherBytes)
        }
    }

    private fun decryptEnvelope(value: String, secretKey: SecretKey = key(false), cipher: Cipher = createCipher()): ByteArray {
        check(value.startsWith(PREFIX)) { "Unsupported storage envelope" }
        val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        return try {
            check(packed.size >= 28) { "Truncated storage envelope" }
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, packed, 0, 12))
            cipher.doFinal(packed, 12, packed.size - 12)
        } finally {
            SecurityUtils.zeroize(packed)
        }
    }

    internal fun newStringCipher(): StringCipher = StringCipher(key(), createCipher())

    fun encrypt(value: String): String = newStringCipher().encrypt(value)

    /** Returns legacy plaintext unchanged, enabling non-destructive migration. */
    fun decrypt(value: String?): String? {
        if (value == null || !isEncrypted(value)) return value
        return StringCipher(key(false), createCipher()).decrypt(value)
    }

    fun isEncrypted(value: String?) = value?.startsWith("enc:") == true

    /** Binary envelope used for private media which must not be left as plaintext files. */
    fun encryptBytes(value: ByteArray): ByteArray {
        val cipher = createCipher()
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value)
        return try {
            byteArrayOf(BINARY_VERSION) + cipher.iv + encrypted
        } finally {
            SecurityUtils.zeroize(encrypted)
        }
    }

    fun decryptBytes(value: ByteArray): ByteArray {
        if (value.isEmpty() || value[0] != BINARY_VERSION) return value
        check(value.size >= 29) { "Truncated binary storage envelope" }
        val cipher = createCipher()
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, value, 1, 12))
        return cipher.doFinal(value, 13, value.size - 13)
    }

    @Synchronized
    fun getOrGenerateDbPassphrase(context: android.content.Context): ByteArray {
        cachedDbPassphrase?.let { return it.clone() }
        val passphrase = loadOrCreateKey(
            P2PPreferences.prefs(context), "db_passphrase_enc", allowLegacy = true,
            generate = {
                val random = ByteArray(32)
                try {
                    java.security.SecureRandom().nextBytes(random)
                    Base64.encode(random, Base64.NO_WRAP)
                } finally {
                    SecurityUtils.zeroize(random)
                }
            },
        )
        return try {
            cachedDbPassphrase = passphrase.clone()
            passphrase.clone()
        } finally {
            SecurityUtils.zeroize(passphrase)
        }
    }

    private val uncommittedKeyEnvelopes = java.util.IdentityHashMap<android.content.SharedPreferences, MutableMap<String, String>>()

    private fun commitKeyEnvelope(preferences: android.content.SharedPreferences, preference: String, envelope: String) {
        uncommittedKeyEnvelopes.getOrPut(preferences) { mutableMapOf() }[preference] = envelope
        check(preferences.edit().putString(preference, envelope).commit()) { "Storage key persistence failed" }
        uncommittedKeyEnvelopes[preferences]?.let { pending ->
            pending.remove(preference)
            if (pending.isEmpty()) uncommittedKeyEnvelopes.remove(preferences)
        }
    }

    @Synchronized
    internal fun loadOrCreateKey(
        preferences: android.content.SharedPreferences,
        preference: String,
        allowLegacy: Boolean = false,
        generate: () -> ByteArray = { ByteArray(32).also { java.security.SecureRandom().nextBytes(it) } },
        encrypt: (ByteArray) -> String = { encryptEnvelope(it) },
        decrypt: (String) -> ByteArray = { decryptEnvelope(it) },
    ): ByteArray {
        uncommittedKeyEnvelopes[preferences]?.get(preference)?.let { envelope ->
            commitKeyEnvelope(preferences, preference, envelope)
        }
        if (preferences.contains(preference)) {
            val stored = checkNotNull(preferences.getString(preference, null)) { "Missing stored key value" }
            val restored = if (isEncrypted(stored)) {
                check(stored.startsWith(PREFIX)) { "Unsupported key envelope" }
                decrypt(stored)
            } else {
                check(allowLegacy && stored.isNotEmpty()) { "Unrecognized stored key format" }
                stored.toByteArray(Charsets.UTF_8)
            }
            return try {
                check(if (allowLegacy) restored.isNotEmpty() else restored.size == 32) { "Invalid stored key length" }
                restored.clone()
            } finally {
                SecurityUtils.zeroize(restored)
            }
        }
        val generated = generate()
        return try {
            check(if (allowLegacy) generated.isNotEmpty() else generated.size == 32) { "Invalid generated key length" }
            commitKeyEnvelope(preferences, preference, encrypt(generated))
            generated.clone()
        } finally {
            SecurityUtils.zeroize(generated)
        }
    }

    /** Helper for fallback attempting decoded binary key if legacy database was created during raw byte window. */
    @Synchronized
    fun getRawDecodedDbPassphraseFallback(context: android.content.Context): ByteArray? {
        val enc = P2PPreferences.prefs(context).getString("db_passphrase_enc", null) ?: return null
        val plainBytes = if (isEncrypted(enc)) decryptEnvelope(enc) else enc.toByteArray(Charsets.UTF_8)
        return try {
            try {
                Base64.decode(plainBytes, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                null
            }
        } finally {
            SecurityUtils.zeroize(plainBytes)
        }
    }

    @Synchronized
    fun clearDbPassphrase() {
        synchronized(this) {
            cachedKey = null
            cachedDbPassphrase?.let {
                SecurityUtils.zeroize(it)
                cachedDbPassphrase = null
            }
        }
        stringDecryptionCache.evictAll()
        com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
    }

    @Synchronized
    fun deleteKey() {
        synchronized(this) {
            cachedKey = null
            cachedDbPassphrase?.let {
                SecurityUtils.zeroize(it)
                cachedDbPassphrase = null
            }
        }
        stringDecryptionCache.evictAll()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private const val PREF_GO_STORAGE_KEY_ENC = "go_storage_key_enc"

    /**
     * Retrieves or generates a 32-byte cryptographic storage key for the native Go engine.
     * The key is protected by non-exportable Android Keystore AES-GCM envelope encryption.
     */
    @Synchronized
    fun getOrGenerateGoStorageKey(context: android.content.Context): ByteArray =
        loadOrCreateKey(P2PPreferences.prefs(context), PREF_GO_STORAGE_KEY_ENC)

    @Synchronized
    fun clearGoStorageKey(context: android.content.Context) {
        P2PPreferences.prefs(context).edit().remove(PREF_GO_STORAGE_KEY_ENC).apply()
    }

    private const val BINARY_VERSION: Byte = 1
}
