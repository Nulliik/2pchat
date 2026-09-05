package com.example.twopchat.relay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ActiveChatNotificationSuppressionTest {

    private val prefMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefMap.clear()
        fakePrefs = TestSharedPreferences(prefMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)
        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        }
        P2PMessageRelay.resetActiveChatForTests()
    }

    @After
    fun tearDown() {
        P2PMessageRelay.resetActiveChatForTests()
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun enterActiveChat_suppressesNotificationWhileOpen() {
        assertFalse(P2PMessageRelay.isChatOpenWith(context, "doggy"))

        val token = P2PMessageRelay.enterActiveChat("doggy")
        try {
            assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))
            assertEquals("doggy", P2PMessageRelay.activeChatPeerName)
        } finally {
            token.close()
        }

        assertFalse(P2PMessageRelay.isChatOpenWith(context, "doggy"))
        assertNull(P2PMessageRelay.activeChatPeerName)
    }

    @Test
    fun enterActiveChat_referenceCountingPreventsPrematureDeactivationDuringNavigationTransition() {
        // Simulates AnimatedContent where incoming and outgoing screens overlap:
        // 1. User enters chat with "doggy" (token1 acquired)
        val token1 = P2PMessageRelay.enterActiveChat("doggy")
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))

        // 2. Animated transition creates incoming screen instance for "doggy" (token2 acquired)
        val token2 = P2PMessageRelay.enterActiveChat("doggy")
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))

        // 3. Animation finishes: outgoing screen is disposed (token1 closed)
        token1.close()

        // 4. Chat MUST still be considered open because token2 is still active!
        // (Previously, token1 disposing cleared activeChatPeer to null, causing bug where notifications popped up)
        assertTrue(
            "Chat must remain open while token2 is held despite token1 disposal",
            P2PMessageRelay.isChatOpenWith(context, "doggy")
        )
        assertEquals("doggy", P2PMessageRelay.activeChatPeerName)

        // 5. When user navigates away from incoming screen, token2 is closed
        token2.close()
        assertFalse(P2PMessageRelay.isChatOpenWith(context, "doggy"))
        assertNull(P2PMessageRelay.activeChatPeerName)
    }

    @Test
    fun isChatOpenWith_resolvesAliasesAndFingerprints() {
        val peerFingerprint = "35564afd8da7c5fb537074e3d64d68e1f7e26900c640d890a1f8a7fb82aaeb92"
        prefMap["peer_fingerprint_doggy"] = peerFingerprint

        val token = P2PMessageRelay.enterActiveChat("doggy")
        try {
            // Exact match
            assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))

            // Case-insensitive match
            assertTrue(P2PMessageRelay.isChatOpenWith(context, "DOGGY"))

            // Cryptographic fingerprint match
            assertTrue(P2PMessageRelay.isChatOpenWith(context, peerFingerprint))

            // Unrelated peer
            assertFalse(P2PMessageRelay.isChatOpenWith(context, "kitty"))

            // Null/blank checks
            assertFalse(P2PMessageRelay.isChatOpenWith(context, null))
            assertFalse(P2PMessageRelay.isChatOpenWith(context, ""))
        } finally {
            token.close()
        }
    }

    @Test
    fun tokenIdempotence_closingTwiceDoesNotUnderflow() {
        val token1 = P2PMessageRelay.enterActiveChat("doggy")
        val token2 = P2PMessageRelay.enterActiveChat("doggy")

        token1.close()
        token1.close() // Duplicate close on same token

        // token2 should still keep chat active
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))

        token2.close()
        assertFalse(P2PMessageRelay.isChatOpenWith(context, "doggy"))
    }

    @Test
    fun fastSwitchingBetweenDifferentPeers_maintainsCorrectState() {
        val tokenDoggy = P2PMessageRelay.enterActiveChat("doggy")
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))
        assertFalse(P2PMessageRelay.isChatOpenWith(context, "kitty"))
        assertEquals("doggy", P2PMessageRelay.activeChatPeerName)

        // Fast switch: user opens kitty before doggy completely disposes
        val tokenKitty = P2PMessageRelay.enterActiveChat("kitty")
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "doggy"))
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "kitty"))

        // Outgoing doggy screen disposes — activeChatPeer must switch to kitty, not null
        tokenDoggy.close()
        assertFalse(P2PMessageRelay.isChatOpenWith(context, "doggy"))
        assertTrue(P2PMessageRelay.isChatOpenWith(context, "kitty"))
        assertEquals("kitty", P2PMessageRelay.activeChatPeerName)

        tokenKitty.close()
        assertFalse(P2PMessageRelay.isChatOpenWith(context, "kitty"))
        assertNull(P2PMessageRelay.activeChatPeerName)
    }

    private class TestSharedPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences, SharedPreferences.Editor {
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((map[key] as? Set<String>)?.toMutableSet()) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = this
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) map[key] = values?.toSet()
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) map.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
