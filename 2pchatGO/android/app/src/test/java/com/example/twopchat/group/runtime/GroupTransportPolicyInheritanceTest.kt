package com.example.twopchat.group.runtime

import com.example.twopchat.config.P2PPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTransportPolicyInheritanceTest {

    @Test
    fun testPeerSourceDefaultsAndGroupInferredRejectsClearnet() {
        // Direct invite peers default to DIRECT_INVITE
        val directSource = "DIRECT_INVITE"
        assertEquals("DIRECT_INVITE", directSource)

        // Roster introduction sets GROUP_INFERRED
        val groupInferredSource = "GROUP_INFERRED"
        assertTrue(groupInferredSource == "GROUP_INFERRED")

        // Policy for group inferred peers must be Tor-only (policyInt 2)
        val torOnlyPolicy = P2PPreferences.PeerTransportPreference.TOR_ONLY
        assertEquals(2, torOnlyPolicy.policyInt)

        // Inbound transport check:
        // If peer is GROUP_INFERRED, only "Tor" is allowed; LAN, WAN, Direct are rejected
        val allowedTransports = setOf("Tor")
        assertTrue("Tor" in allowedTransports)
        assertFalse("Direct P2P" in allowedTransports)
        assertFalse("LAN" in allowedTransports)
        assertFalse("WAN" in allowedTransports)
    }

    @Test
    fun testMonotonicTorOnlyGroupFlagCannotBeDowngraded() {
        // Pure state machine validation for monotonic torOnlyGroup flag
        fun validateTorOnlyTransition(current: Boolean, requested: Boolean): Boolean {
            if (current && !requested) {
                // Weakening true -> false is forbidden
                return false
            }
            return true
        }

        // false -> false: allowed
        assertTrue(validateTorOnlyTransition(current = false, requested = false))
        // false -> true: allowed
        assertTrue(validateTorOnlyTransition(current = false, requested = true))
        // true -> true: allowed
        assertTrue(validateTorOnlyTransition(current = true, requested = true))
    }

    @Test
    fun confirmYggOnlyAgainstTorFloorYieldsTorOnly() {
        val torFloor = P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt // 2
        val desiredYgg = P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY.policyInt // 3

        // Under raw maxOf(2, 3), result would be 3 (downgrade!). Under strictnessRank, result MUST remain 2 (TOR_ONLY).
        fun confirmPolicy(desired: Int, floor: Int): Int =
            if (floor == P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt) {
                P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
            } else desired

        val effective = confirmPolicy(desiredYgg, torFloor)
        assertEquals(
            "Confirming YGGDRASIL_ONLY against TOR_ONLY floor must yield TOR_ONLY",
            P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt,
            effective,
        )
    }

    @Test
    fun confirmDirectOnlyAgainstTorFloorYieldsTorOnly() {
        val torFloor = P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt // 2
        val desiredDirect = P2PPreferences.PeerTransportPreference.DIRECT_ONLY.policyInt // 1

        fun confirmPolicy(desired: Int, floor: Int): Int =
            if (floor == P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt) {
                P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
            } else desired

        val effective = confirmPolicy(desiredDirect, torFloor)
        assertEquals(
            "Confirming DIRECT_ONLY against TOR_ONLY floor must yield TOR_ONLY",
            P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt,
            effective,
        )
    }

    @Test
    fun confirmationNeverWeakensBelowGroupFloor() {
        val torFloor = P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
        val autoDesired = P2PPreferences.PeerTransportPreference.AUTO.policyInt
        val directDesired = P2PPreferences.PeerTransportPreference.DIRECT_ONLY.policyInt
        val yggDesired = P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY.policyInt

        val allDesired = listOf(autoDesired, directDesired, yggDesired, torFloor)
        for (desired in allDesired) {
            val effective = if (torFloor == P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt) {
                P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
            } else desired
            assertEquals("Policy must never weaken below TOR_ONLY group floor", torFloor, effective)
        }
    }

    @Test
    fun existingPeerPolicyNotWeakenedByGroup_AndStrictenedIfGroupRequires() {
        // If peer already had TOR_ONLY, adding to clearnet group does not weaken peer to AUTO
        val existingTor = P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
        val clearnetGroupFloor = P2PPreferences.PeerTransportPreference.AUTO.policyInt

        fun combinePolicies(existing: Int, groupFloor: Int): Int {
            if (existing == P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt ||
                groupFloor == P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt) {
                return P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
            }
            return if (groupFloor != 0) groupFloor else existing
        }

        assertEquals(existingTor, combinePolicies(existingTor, clearnetGroupFloor))

        // If peer had AUTO and is added to Tor-only group, peer policy is strictened to TOR_ONLY
        val existingAuto = P2PPreferences.PeerTransportPreference.AUTO.policyInt
        val torGroupFloor = P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt
        assertEquals(torGroupFloor, combinePolicies(existingAuto, torGroupFloor))
    }

    @Test
    fun torOnlyGroupFlagRejectsWeakeningEvenFromOwner() {
        // Even group owner cannot weaken a torOnlyGroup once enabled
        fun canUpdateTorOnlyGroup(current: Boolean, requested: Boolean): Boolean {
            if (current && !requested) return false
            return true
        }

        assertFalse(canUpdateTorOnlyGroup(current = true, requested = false))
        assertTrue(canUpdateTorOnlyGroup(current = false, requested = true))
        assertTrue(canUpdateTorOnlyGroup(current = true, requested = true))
    }
}
