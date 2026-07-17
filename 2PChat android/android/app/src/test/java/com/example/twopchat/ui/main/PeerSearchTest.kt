package com.example.twopchat.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerSearchTest {
    @Test
    fun `splits searchable address into nickname and discovery code`() {
        assertEquals(
            PeerSearchAddress("Alice", "abcd-2345"),
            parsePeerSearchAddress("  Alice # abcd-2345  "),
        )
    }

    @Test
    fun `uses the last separator so nickname may contain hash`() {
        assertEquals(
            PeerSearchAddress("Alice#mobile", "abcd-2345"),
            parsePeerSearchAddress("Alice#mobile#abcd-2345"),
        )
    }

    @Test
    fun `rejects incomplete searchable addresses`() {
        assertNull(parsePeerSearchAddress("Alice"))
        assertNull(parsePeerSearchAddress("#abcd-2345"))
        assertNull(parsePeerSearchAddress("Alice#"))
    }

    @Test
    fun `maps bridge nickname and keeps live verified result connectable`() {
        val contact = contactFromPeerSearchResult(
            mapOf(
                "nickname" to "Alice",
                "fingerprint" to "fingerprint-1",
                "endpoints" to listOf("192.0.2.10:50001", "[200:db8::1]:50001"),
                "verified" to "True",
                "ownership_verified" to "False",
                "verification_reason" to "authenticated live response",
            ),
            appLanguage = "English",
        )

        assertEquals("Alice", contact.name)
        assertEquals("AL", contact.initials)
        assertEquals("fingerprint-1", contact.fingerprint)
        assertEquals("192.0.2.10:50001,[200:db8::1]:50001", contact.endpoints)
        assertTrue(contact.verified)
        assertFalse(contact.ownershipVerified)
        assertEquals("Live node and key · nickname ownership unverified", contact.status)
    }
}
