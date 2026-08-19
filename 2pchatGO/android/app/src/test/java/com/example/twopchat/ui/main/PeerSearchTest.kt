package com.example.twopchat.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.twopchat.orderedDirectEndpoints
import com.example.twopchat.selectExternalIpv4

class PeerSearchTest {
    @Test
    fun `splits searchable address into nickname and discovery code`() {
        assertEquals(
            PeerSearchAddress("Alice", "abcd-2345"),
            parsePeerSearchAddress("  Alice # abcd-2345  "),
        )
    }

    @Test
    fun `normalizes multi-word searchable nickname`() {
        assertEquals(
            PeerSearchAddress("Alice_Smith", "abcd-2345"),
            parsePeerSearchAddress("Alice   Smith#abcd-2345"),
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
    fun `accepts 32 code points and rejects an overlong search name`() {
        val maxName = "a".repeat(31) + "😀"
        assertEquals(
            PeerSearchAddress(maxName, "code"),
            parsePeerSearchAddress("$maxName#code"),
        )
        assertNull(parsePeerSearchAddress("${maxName}x#code"))
    }

    @Test
    fun `QR request searches tracker by nickname and shares the QR code`() {
        assertEquals(
            PeerSearchRequest(
                lookupNickname = "Alice_Smith",
                sharedCode = "abcd-2345",
                expectedLiveName = "Alice_Smith",
                expectedFingerprint = "fingerprint",
            ),
            invitePeerSearchRequest(" Alice Smith ", " abcd-2345 ", "fingerprint"),
        )
    }

    @Test
    fun `classic and QR paths use the same tracker namespace`() {
        val classic = classicPeerSearchRequest("Alice Smith#abcd-2345")
        val qr = invitePeerSearchRequest("Alice Smith", "abcd-2345", null)

        assertEquals(classic, qr)
    }

    @Test
    fun `contact invite scheme is whitespace and case tolerant`() {
        assertTrue(isContactInviteLink("  2PCHAT://CONNECT?name=Alice&code=abcd  "))
        assertFalse(isContactInviteLink("https://example.test/2pchat://connect"))
    }

    @Test
    fun `QR request requires both nickname and connection code`() {
        assertNull(invitePeerSearchRequest(null, "code", null))
        assertNull(invitePeerSearchRequest("Alice", " ", null))
    }

    @Test
    fun `QR request accepts direct Tor onion invite without connection code`() {
        val req = invitePeerSearchRequest("bubba", null, null, "lt5ow44yv2l4pwkv642haddbigrdqii5vd6uhomznd7xzpgpi5tpbqid.onion:50001", null)
        assertNotNull(req)
        assertEquals("bubba", req?.expectedLiveName)
    }

    @Test
    fun `formats direct IPv4 and Yggdrasil QR candidates`() {
        assertEquals("192.0.2.10:50001", formatInviteEndpoint("192.0.2.10"))
        assertEquals("192.0.2.10:4242", formatInviteEndpoint("192.0.2.10:4242"))
        assertEquals("[200:db8::10]:50001", formatInviteEndpoint("200:db8::10"))
        assertEquals("[200:db8::10]:4242", formatInviteEndpoint("[200:db8::10]:4242"))
        assertNull(formatInviteEndpoint("tracker.example"))
    }

    @Test
    fun `orders QR routes as local IPv4 then external IPv4 then IPv6`() {
        assertEquals(
            listOf(
                "192.168.1.20:50001",
                "203.0.113.20:50001",
                "[200:db8::20]:50001",
            ),
            orderedDirectEndpoints(listOf(
                "[200:db8::20]:50001",
                "203.0.113.20:50001",
                "192.168.1.20:50001",
            )),
        )
    }

    @Test
    fun `selects a distinct usable external IPv4 for QR`() {
        assertEquals(
            "203.0.113.20",
            selectExternalIpv4(
                "192.168.1.20",
                listOf("200:db8::20", "192.168.1.20", "203.0.113.20"),
            ),
        )
    }

    @Test
    fun `QR only connects to a live verified tracker result`() {
        val live = mapOf<String, Any>("verified" to "True", "ownership_verified" to "False")
        val owned = mapOf<String, Any>("verified" to "True", "ownership_verified" to "True")
        val stale = mapOf<String, Any>("verified" to "False", "ownership_verified" to "True")

        assertTrue(isConnectablePeerSearchResult(live, expectedFingerprint = null))
        assertFalse(isConnectablePeerSearchResult(live, expectedFingerprint = "expected"))
        assertTrue(isConnectablePeerSearchResult(owned, expectedFingerprint = "expected"))
        assertFalse(isConnectablePeerSearchResult(stale, expectedFingerprint = null))
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
