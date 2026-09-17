package com.example.twopchat.security

import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class SecureStorageKeyEnvelopeTest {

    private val store = mutableMapOf<String, Any?>()

    private val prefs = object : android.content.SharedPreferences,
        android.content.SharedPreferences.Editor {
        override fun getAll(): MutableMap<String, *> = HashMap(store)
        override fun getString(key: String?, defValue: String?): String? = (store[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = this
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
            if (key != null) store[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            if (key != null) store.remove(key)
            return this
        }
        override fun clear(): android.content.SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun encryptFailurePropagatesInsteadOfReturningPlaintext() {
        val exception = java.security.GeneralSecurityException("keystore unavailable")
        val error = runCatching {
            SecureStorage.loadOrCreateKey(
                prefs,
                "k",
                generate = { ByteArray(32) },
                encrypt = { throw exception },
            )
        }.exceptionOrNull()
        assertEquals(exception, error)
        assertFalse(prefs.contains("k"))
    }

    @Test
    fun failedCommitRetriesSameInMemoryEnvelopeBeforeReturningKey() {
        for (legacy in listOf(false, true)) {
            val committed = AtomicCommitPrefs(mutableMapOf())
            val raw = ByteArray(if (legacy) 44 else 32) { 7 }
            var generations = 0
            var encryptions = 0
            val envelope = "enc:v1:pending-$legacy"
            fun load() = SecureStorage.loadOrCreateKey(
                committed, "k", allowLegacy = legacy,
                generate = { generations++; raw.clone() },
                encrypt = { encryptions++; envelope },
                decrypt = { assertEquals(envelope, it); raw.clone() },
            )
            repeat(2) {
                org.junit.Assert.assertThrows(IllegalStateException::class.java) { load() }
                assertEquals(envelope, committed.getString("k", null))
                assertEquals(it + 1, committed.commits)
                assertFalse(committed.durable.containsKey("k"))
            }
            committed.succeeds = true
            assertArrayEquals(raw, load())
            assertEquals(envelope, committed.durable["k"])
            assertEquals(3, committed.commits)
            assertEquals(1, generations)
            assertEquals(1, encryptions)
            assertArrayEquals(raw, load())
            assertEquals(3, committed.commits)
        }
    }

    @Test
    fun decryptionFailurePropagatesInsteadOfReturningCiphertext() {
        store["k"] = "enc:v1:not-a-valid-envelope"
        val error = runCatching {
            SecureStorage.loadOrCreateKey(prefs, "k")
        }.exceptionOrNull()
        assertTrue(error != null)
        assertEquals("enc:v1:not-a-valid-envelope", store["k"])
        assertFalse(store.containsKey("fresh_marker"))
    }

    @Test
    fun existing32ByteEnvelopeIsRestoredNotReplaced() {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        val envelope = "enc:v1:" + android.util.Base64.encodeToString(
            byteArrayOf(1) + raw, android.util.Base64.NO_WRAP,
        )
        store["k"] = envelope
        val error = runCatching {
            SecureStorage.loadOrCreateKey(prefs, "k", decrypt = { error("injected decrypt failure") })
        }.exceptionOrNull()
        assertTrue("envelope failure must propagate", error is IllegalStateException)
        assertEquals(envelope, store["k"])
    }

    @Test
    fun legacyPlainKeyFormatIsStillAcceptedWithoutRegeneration() {
        val legacy = "legacy-passphrase-value"
        store["k"] = legacy
        var decrypted = false
        val restored = SecureStorage.loadOrCreateKey(
            prefs, "k", allowLegacy = true, decrypt = { decrypted = true; ByteArray(0) },
        )
        assertArrayEquals(legacy.toByteArray(Charsets.UTF_8), restored)
        assertFalse(decrypted)
        assertEquals(legacy, store["k"])
    }

    @Test
    fun goStorageKeyLengthIsEnforced() {
        var generated = false
        val error = runCatching {
            SecureStorage.loadOrCreateKey(
                prefs, "go", generate = { generated = true; ByteArray(31) },
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(generated)
        assertFalse(store.containsKey("go"))
    }

    @Test
    fun generatedKeyIsZeroizedAfterPersistedReturn() {
        val sensitive = ByteArray(32) { 7 }
        var leaked: ByteArray? = null
        SecureStorage.loadOrCreateKey(
            prefs, "k",
            generate = { sensitive },
            encrypt = { bytes ->
                leaked = bytes.copyOf()
                "enc:v1:ciphertext"
            },
        )
        assertEquals(32, checkNotNull(leaked).size)
        assertTrue("generated key must be zeroized after persistence", sensitive.all { it == 0.toByte() })
    }

    @Test
    fun restoredKeyIsZeroizedAfterReturn() {
        val handed = ByteArray(32) { 9 }
        store["k"] = "enc:v1:restored"
        val returned = SecureStorage.loadOrCreateKey(prefs, "k", decrypt = { handed })
        assertTrue(returned.all { it == 9.toByte() })
        assertTrue("restored key buffer must be zeroized after return", handed.all { it == 0.toByte() })
    }

    private class AtomicCommitPrefs(
        private val backing: MutableMap<String, Any?>,
    ) : android.content.SharedPreferences by AtomicCommitPrefsDelegate(backing) {
        var succeeds = false
        var commits = 0
        val durable = mutableMapOf<String, Any?>()
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
            override fun remove(key: String?): android.content.SharedPreferences.Editor = this
            override fun clear(): android.content.SharedPreferences.Editor = this
            override fun commit(): Boolean {
                commits++
                backing.putAll(changes)
                if (succeeds) durable.putAll(backing)
                return succeeds
            }
            override fun apply() {}
        }
    }

    private companion object {
        fun AtomicCommitPrefsDelegate(backing: MutableMap<String, Any?>): android.content.SharedPreferences =
            java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "contains" -> backing.containsKey(args?.get(0) as? String)
                    "getString" -> (backing[args?.get(0) as? String] as? String)
                    "edit" -> error("editor replaced")
                    else -> defaultReturnValue(method)
                }
            } as android.content.SharedPreferences

        fun defaultReturnValue(method: java.lang.reflect.Method): Any? = when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            else -> null
        }
    }
}
