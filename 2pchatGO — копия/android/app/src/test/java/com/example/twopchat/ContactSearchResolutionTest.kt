package com.example.twopchat

import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.ui.main.resolvePeerKeyForContact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ContactSearchResolutionTest {

    private val prefsMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences

    @Before
    fun setUp() {
        prefsMap.clear()
        fakePrefs = createFakeSharedPreferences(prefsMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)
    }

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun testResearchingExistingContactWithEmptyFingerprintReturnsCleanName() {
        // Given: User already has "doggy" in active chats and a real fingerprint stored in prefs
        val activeChats = setOf("doggy")
        prefsMap["peer_fingerprint_doggy"] = "A1B2C3D4E5F67890"

        // When: Search returns "doggy" with an empty fingerprint (e.g. from initial tracker search result)
        val resolvedKey = resolvePeerKeyForContact(
            context = createDummyContext(),
            contactName = "doggy",
            contactFingerprint = "",
            contactEndpoints = "192.168.1.10:50001",
            activeChats = activeChats,
            sharedPrefs = fakePrefs,
        )

        // Then: The exact existing chat name "doggy" MUST be returned (NOT "doggy · ")
        assertEquals("doggy", resolvedKey)
    }

    @Test
    fun testResearchingExistingContactWithMatchingFingerprintReturnsCleanName() {
        // Given: User already has "Foxxxy" in active chats
        val activeChats = setOf("Foxxxy")
        prefsMap["peer_fingerprint_Foxxxy"] = "FOXXXXY_FINGERPRINT_12345"

        // When: Search returns "Foxxxy" with matching fingerprint
        val resolvedKey = resolvePeerKeyForContact(
            context = createDummyContext(),
            contactName = "Foxxxy",
            contactFingerprint = "FOXXXXY_FINGERPRINT_12345",
            contactEndpoints = "192.168.1.20:50001",
            activeChats = activeChats,
            sharedPrefs = fakePrefs,
        )

        // Then: Reuses "Foxxxy"
        assertEquals("Foxxxy", resolvedKey)
    }

    @Test
    fun testAddNewContactWithoutCollisionReturnsCleanName() {
        // Given: No contacts in active chats
        val activeChats = emptySet<String>()

        // When: Adding a new contact "Bob"
        val resolvedKey = resolvePeerKeyForContact(
            context = createDummyContext(),
            contactName = "Bob",
            contactFingerprint = "BOB_FINGERPRINT_99999",
            contactEndpoints = "192.168.1.30:50001",
            activeChats = activeChats,
            sharedPrefs = fakePrefs,
        )

        // Then: Returns clean name "Bob"
        assertEquals("Bob", resolvedKey)
    }

    @Test
    fun testRealCollisionWithDifferentFingerprintsAppends8CharHashNotTrailingDot() {
        // Given: User has "Alice" with key FP_AAA
        val activeChats = setOf("Alice")
        prefsMap["peer_fingerprint_Alice"] = "FP_AAA_ORIGINAL_KEY_12345"

        // When: A different user named "Alice" with key FP_BBB is discovered
        val resolvedKey = resolvePeerKeyForContact(
            context = createDummyContext(),
            contactName = "Alice",
            contactFingerprint = "FP_BBB_NEW_KEY_67890",
            contactEndpoints = "192.168.1.40:50001",
            activeChats = activeChats,
            sharedPrefs = fakePrefs,
        )

        // Then: Disambiguates with 8-character fingerprint suffix, never empty or dangling space
        assertEquals("Alice · FP_BBB_N", resolvedKey)
        assertTrue(resolvedKey.startsWith("Alice · "))
        assertTrue(!resolvedKey.endsWith(" · ") && !resolvedKey.endsWith(" ·"))
    }

    @Test
    fun testTrailingWhitespaceOrEmptyContactDefaultsSafely() {
        val resolved = resolvePeerKeyForContact(
            context = createDummyContext(),
            contactName = "   ",
            contactFingerprint = "",
            contactEndpoints = "",
            activeChats = emptySet(),
            sharedPrefs = fakePrefs,
        )
        assertEquals("Unknown", resolved)
    }

    private fun createDummyContext(): android.content.Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        }
    }

    private fun createFakeSharedPreferences(map: MutableMap<String, Any?>): SharedPreferences {
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> {
                    val key = args[0] as String
                    val def = args.getOrNull(1) as? String
                    (map[key] as? String) ?: def
                }
                "getStringSet" -> {
                    val key = args[0] as String
                    @Suppress("UNCHECKED_CAST")
                    val def = args.getOrNull(1) as? Set<String>
                    @Suppress("UNCHECKED_CAST")
                    (map[key] as? Set<String>) ?: def
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
