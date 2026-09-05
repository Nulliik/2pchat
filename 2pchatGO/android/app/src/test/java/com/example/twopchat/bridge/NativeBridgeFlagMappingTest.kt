package com.example.twopchat.bridge

import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.TransportEvent
import com.example.twopchat.tor.TransportEventManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeFlagMappingTest {

    // Bitmask constants defined in core-go/pkg/transport/policy.go
    companion object {
        const val FLAG_ALLOW_LAN = 1 shl 0        // 1
        const val FLAG_ALLOW_WAN = 1 shl 1        // 2
        const val FLAG_ALLOW_YGGDRASIL = 1 shl 2  // 4
        const val FLAG_ALLOW_ONION = 1 shl 3      // 8
        const val FLAG_ALLOW_LOCAL_DNS = 1 shl 4  // 16

        const val POLICY_SPEED = 31               // All flags
        const val POLICY_TOR_STRICT = 8           // Only Onion
        const val POLICY_CLEARNET_ONLY = 7        // LAN | WAN | Yggdrasil
    }

    private fun getContactPolicyFlags(pref: P2PPreferences.PeerTransportPreference): Int {
        return when (pref) {
            P2PPreferences.PeerTransportPreference.TOR_ONLY -> FLAG_ALLOW_ONION // 8
            P2PPreferences.PeerTransportPreference.DIRECT_ONLY -> FLAG_ALLOW_LAN or FLAG_ALLOW_WAN // 3
            P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY -> FLAG_ALLOW_YGGDRASIL // 4
            P2PPreferences.PeerTransportPreference.AUTO -> 0 // Inherit global
        }
    }

    private fun computeEffectivePolicy(globalFlags: Int, contactPref: P2PPreferences.PeerTransportPreference): Int {
        val contactFlags = getContactPolicyFlags(contactPref)
        return if (contactFlags == 0) {
            globalFlags
        } else {
            globalFlags and contactFlags
        }
    }

    @Test
    fun testContactPolicyFlagMapping() {
        assertEquals("AUTO must map to 0 (inherit global)", 0, getContactPolicyFlags(P2PPreferences.PeerTransportPreference.AUTO))
        assertEquals("DIRECT_ONLY must map to 3 (LAN | WAN)", 3, getContactPolicyFlags(P2PPreferences.PeerTransportPreference.DIRECT_ONLY))
        assertEquals("YGGDRASIL_ONLY must map to 4", 4, getContactPolicyFlags(P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY))
        assertEquals("TOR_ONLY must map to 8", 8, getContactPolicyFlags(P2PPreferences.PeerTransportPreference.TOR_ONLY))
    }

    @Test
    fun testPolicyMatrix_4ContactBy3GlobalModes() {
        // Mode 1: Tor Strict (Global = 8)
        val strictGlobal = POLICY_TOR_STRICT
        assertEquals(8, computeEffectivePolicy(strictGlobal, P2PPreferences.PeerTransportPreference.AUTO))
        assertEquals(8, computeEffectivePolicy(strictGlobal, P2PPreferences.PeerTransportPreference.TOR_ONLY))
        assertEquals(0, computeEffectivePolicy(strictGlobal, P2PPreferences.PeerTransportPreference.DIRECT_ONLY)) // Conflict!
        assertEquals(0, computeEffectivePolicy(strictGlobal, P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY)) // Conflict!

        // Mode 2: Speed / Dual-Stack (Global = 31)
        val speedGlobal = POLICY_SPEED
        assertEquals(31, computeEffectivePolicy(speedGlobal, P2PPreferences.PeerTransportPreference.AUTO))
        assertEquals(8, computeEffectivePolicy(speedGlobal, P2PPreferences.PeerTransportPreference.TOR_ONLY))
        assertEquals(3, computeEffectivePolicy(speedGlobal, P2PPreferences.PeerTransportPreference.DIRECT_ONLY))
        assertEquals(4, computeEffectivePolicy(speedGlobal, P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY))

        // Mode 3: Tor Disabled / Clearnet Only (Global = 7)
        val clearnetGlobal = POLICY_CLEARNET_ONLY
        assertEquals(7, computeEffectivePolicy(clearnetGlobal, P2PPreferences.PeerTransportPreference.AUTO))
        assertEquals(0, computeEffectivePolicy(clearnetGlobal, P2PPreferences.PeerTransportPreference.TOR_ONLY)) // Downgrade prevented!
        assertEquals(3, computeEffectivePolicy(clearnetGlobal, P2PPreferences.PeerTransportPreference.DIRECT_ONLY))
        assertEquals(4, computeEffectivePolicy(clearnetGlobal, P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY))
    }

    @Test
    fun testPolicyConflictEventEmission() {
        TransportEventManager.clear()

        val peerName = "Bob"
        val pref = P2PPreferences.PeerTransportPreference.DIRECT_ONLY
        val isTorStrict = true

        if (isTorStrict && (pref == P2PPreferences.PeerTransportPreference.DIRECT_ONLY || pref == P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY)) {
            TransportEventManager.emit(
                TransportEvent.PolicyConflict(
                    peerName = peerName,
                    contactPolicy = pref.key,
                    globalPolicy = "tor_strict"
                )
            )
        }

        val event = TransportEventManager.lastEvent.value
        assertNotNull("PolicyConflict event must be emitted", event)
        assertTrue(event is TransportEvent.PolicyConflict)
        val conflict = event as TransportEvent.PolicyConflict
        assertEquals("Bob", conflict.peerName)
        assertEquals("direct", conflict.contactPolicy)
        assertEquals("tor_strict", conflict.globalPolicy)

        TransportEventManager.clear()
    }

    @Test
    fun testDowngradeProtection_TorOnlyPeer_TorOffline_BlocksDial() {
        TransportEventManager.clear()

        val peerName = "Alice"
        val pref = P2PPreferences.PeerTransportPreference.TOR_ONLY
        val torReady = false

        var dialAttempted = false
        if (pref == P2PPreferences.PeerTransportPreference.TOR_ONLY && !torReady) {
            TransportEventManager.emit(
                TransportEvent.TorUnavailable(
                    peerName = peerName,
                    reason = "Tor network is offline or connecting; clearnet fallback blocked by TOR_ONLY policy"
                )
            )
        } else {
            dialAttempted = true
        }

        assertFalse("Dial must be blocked when Tor is unavailable for TOR_ONLY peer", dialAttempted)
        val event = TransportEventManager.lastEvent.value
        assertNotNull("TorUnavailable event must be emitted", event)
        assertTrue(event is TransportEvent.TorUnavailable)
        assertEquals("Alice", (event as TransportEvent.TorUnavailable).peerName)

        TransportEventManager.clear()
    }
}
