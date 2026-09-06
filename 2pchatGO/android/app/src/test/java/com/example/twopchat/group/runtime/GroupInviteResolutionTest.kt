package com.example.twopchat.group.runtime

import android.content.Context
import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class GroupInviteResolutionTest {

    private val prefsMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var fakeContext: Context

    @Before
    fun setUp() {
        prefsMap.clear()
        fakePrefs = createFakeSharedPreferences(prefsMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)
        fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        }
    }

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun getPeerFingerprintResolvesExactMatch() {
        val fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        prefsMap[P2PPreferences.peerFingerprint("Alice")] = fp

        val resolved = P2PPreferences.getPeerFingerprint(fakeContext, "Alice")
        assertEquals(fp, resolved)
    }

    @Test
    fun getPeerFingerprintResolvesTaggedPeerNameToBasePeerFingerprint() {
        val fp = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        prefsMap[P2PPreferences.peerFingerprint("Alice")] = fp

        // When invited as "Alice#1234" or "Alice · 192.168.1.5"
        val resolvedWithHashTag = P2PPreferences.getPeerFingerprint(fakeContext, "Alice#1234")
        assertEquals("Fingerprint for Alice#1234 must resolve to base Alice", fp, resolvedWithHashTag)

        val resolvedWithDotTag = P2PPreferences.getPeerFingerprint(fakeContext, "Alice · 192.168.1.5")
        assertEquals("Fingerprint for Alice · 192.168.1.5 must resolve to base Alice", fp, resolvedWithDotTag)
    }

    @Test
    fun getPeerFingerprintResolvesRawFingerprintDirectly() {
        val rawFp = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"
        val resolved = P2PPreferences.getPeerFingerprint(fakeContext, rawFp)
        assertEquals("Raw 64-character hex fingerprint must resolve directly", rawFp, resolved)
    }

    @Test
    fun getPeerFingerprintReturnsNullForUnknownPeer() {
        val resolved = P2PPreferences.getPeerFingerprint(fakeContext, "NonExistentUser")
        assertNull(resolved)
    }

    private fun createFakeSharedPreferences(map: MutableMap<String, Any?>): SharedPreferences {
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> {
                    val key = args[0] as String
                    val def = args.getOrNull(1) as? String
                    (map[key] as? String) ?: def
                }
                "contains" -> {
                    val key = args[0] as String
                    map.containsKey(key)
                }
                "getAll" -> map.toMap()
                else -> null
            }
        } as SharedPreferences
    }
}
