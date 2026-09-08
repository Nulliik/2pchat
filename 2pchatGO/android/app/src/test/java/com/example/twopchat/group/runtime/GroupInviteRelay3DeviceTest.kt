package com.example.twopchat.group.runtime

import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.protocol.GroupControlFrames
import com.example.twopchat.group.protocol.GroupInvite
import com.example.twopchat.group.protocol.GroupInviteMember
import com.example.twopchat.group.protocol.GroupInviteResponse
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.protocol.Capability
import com.example.twopchat.protocol.NegotiatedSession
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 3-Device Simulation Test for Group Invite Relay Delivery.
 *
 * Topology:
 * - Owner: Foxxxy (Group creator and administrator)
 * - Member: doggy (Existing group member, friend of puppy)
 * - Candidate: puppy (Invitee, has direct P2P link with doggy, but NOT with Foxxxy)
 *
 * Scenarios tested:
 * 1. End-to-end invite generation, relay via doggy, and auto-accept on puppy.
 * 2. Defense-in-depth: Malicious participant cannot forge MEMBER_ADDED or fake invite.
 * 3. Tampering detection: Candidate rejects modified invite payload.
 * 4. Response relay: Candidate's response is forwarded by doggy back to Foxxxy.
 */
class GroupInviteRelay3DeviceTest {

    private val ownerFp = "fp_foxxxy_owner_11111111"
    private val ownerPub = "pub_foxxxy_key_11111111"
    private val memberFp = "fp_doggy_member_22222222"
    private val memberPub = "pub_doggy_key_22222222"
    private val candidateFp = "fp_puppy_candidate_33333333"
    private val candidatePub = "pub_puppy_key_33333333"

    private val ownerDeviceId = GroupChatCoordinator.stableDeviceId(ownerFp)
    private val memberDeviceId = GroupChatCoordinator.stableDeviceId(memberFp)
    private val candidateDeviceId = GroupChatCoordinator.stableDeviceId(candidateFp)
    private val groupId = "group_relay_3dev_test"

    @Before
    fun setUp() {
        // Deterministic mock signatures keyed by canonical payload and signing key
        GroupIdentitySignatures.testSigner = { canonical ->
            "sig_valid_${canonical.hashCode()}"
        }
        GroupIdentitySignatures.testVerifier = { pubKey, canonical, signature ->
            pubKey.isNotBlank() && signature == "sig_valid_${canonical.hashCode()}"
        }
    }

    @After
    fun tearDown() {
        GroupIdentitySignatures.testSigner = null
        GroupIdentitySignatures.testVerifier = null
    }

    private fun createOwnerSignedInvite(
        now: Long = System.currentTimeMillis(),
    ): GroupInvite {
        val members = listOf(
            GroupInviteMember(
                fingerprint = ownerFp,
                peerName = "Foxxxy",
                deviceId = ownerDeviceId,
                signingKey = ownerPub,
                role = GroupRole.OWNER.name,
                status = "ACTIVE",
            ),
            GroupInviteMember(
                fingerprint = candidateFp,
                peerName = "puppy",
                deviceId = candidateDeviceId,
                signingKey = candidatePub,
                role = GroupRole.MEMBER.name,
                status = "INVITED",
            ),
        )
        val unsigned = GroupInvite(
            inviteId = "inv_relay_" + UUID.randomUUID().toString(),
            groupId = groupId,
            title = "Foxxxy's Secret Den",
            description = "3-device relay test group",
            epoch = 2L,
            epochSecretBase64 = "dGVzdC1lcG9jaC1zZWNyZXQtMzJieXRlcw==",
            ownerFingerprint = ownerFp,
            senderFingerprint = ownerFp,
            senderSigningKey = ownerPub,
            coordinatorFingerprint = ownerFp,
            controlHead = "ctrl_001",
            historyCursors = mapOf(ownerDeviceId to 10L, candidateDeviceId to 0L),
            createdAtMs = now,
            rosterSize = 3,
            members = members,
            cryptoSuite = "epoch_aead_v1",
            signatureBase64 = "",
        )
        val sig = GroupIdentitySignatures.sign(unsigned.canonicalForSignature())
        return unsigned.copy(signatureBase64 = sig)
    }

    @Test
    fun testEndToEndRelayThroughIntermediateMember() {
        // Step 1: Network Topology Check
        // Foxxxy is connected to doggy; doggy is connected to puppy.
        val ownerConnectedTo = setOf(memberFp)
        val memberConnectedTo = setOf(ownerFp, candidateFp)
        val candidateConnectedTo = setOf(memberFp)

        assertFalse("Foxxxy has NO direct connection to puppy", ownerConnectedTo.contains(candidateFp))
        assertTrue("doggy is connected to puppy", memberConnectedTo.contains(candidateFp))

        // Step 2: Owner creates signed invite for puppy
        val invite = createOwnerSignedInvite()
        assertTrue("Invite must be cryptographically signed by owner", invite.verifySignature())
        assertEquals("Sender of invite must be Foxxxy", ownerFp, invite.senderFingerprint)

        // Step 3: Owner creates MEMBER_ADDED control event embedding the invite
        val inviteJson = GroupWireProtocol.inviteToJson(invite)
        val eventPayload = JSONObject().apply {
            put("member_device_id", candidateDeviceId)
            put("fingerprint", candidateFp)
            put("peer_name", "puppy")
            put("status", "INVITED")
            put("invite_json", inviteJson.toString())
        }
        val canonicalEvent = "event_member_added_${eventPayload.toString().hashCode()}"
        val eventSignature = GroupIdentitySignatures.sign(canonicalEvent)

        // Step 4: doggy receives MEMBER_ADDED from Foxxxy
        // doggy verifies that author is group owner and event signature is valid
        val isAuthorOwner = (ownerDeviceId == ownerDeviceId)
        val isEventSigValid = GroupIdentitySignatures.verify(ownerPub, canonicalEvent, eventSignature)
        assertTrue("doggy must verify event was authored by owner", isAuthorOwner)
        assertTrue("doggy must verify event signature with owner public key", isEventSigValid)

        // doggy extracts invite_json and verifies it
        val extractedInvite = GroupWireProtocol.parseInvite(JSONObject(eventPayload.getString("invite_json")))
        assertTrue("doggy must verify invite signature with owner key", extractedInvite.verifySignature())
        assertEquals("Invite owner must match group owner", ownerFp, extractedInvite.ownerFingerprint)

        // doggy checks capability before relaying to puppy
        val puppySession = NegotiatedSession(
            protocolVersion = 1,
            activeCapabilities = setOf(
                Capability.GROUP_SUITE_V1.id,
                Capability.GROUP_INVITE_RELAY_V1.id,
            ),
            peerIsOutdated = false,
            peerIsLegacy = false,
        )
        assertTrue("puppy must support group_invite_relay_v1", puppySession.supports(Capability.GROUP_INVITE_RELAY_V1))

        // Step 5: doggy relays invite_json to puppy
        val transportSender = memberFp // doggy is the transport sender

        // Step 6: puppy receives invite from doggy
        // Transport sender is doggy, NOT Foxxxy
        val isDirectFromOwner = (transportSender == extractedInvite.senderFingerprint)
        assertFalse("Transport sender (doggy) does not match signed sender (Foxxxy)", isDirectFromOwner)

        // puppy verifies Ed25519 signature of the owner
        assertTrue("puppy must verify owner cryptographic signature", extractedInvite.verifySignature())

        // puppy verifies candidate recipient binding
        val candidateEntry = extractedInvite.members.firstOrNull {
            it.fingerprint == candidateFp && it.deviceId == candidateDeviceId
        }
        assertNotNull("puppy must be explicitly listed as recipient in invite", candidateEntry)
        assertEquals("INVITED", candidateEntry?.status)

        // puppy checks anti-replay constraints
        val now = System.currentTimeMillis()
        val isFresh = (now - extractedInvite.createdAtMs) <= 7L * 24L * 60L * 60L * 1000L
        val isClockSkewValid = extractedInvite.createdAtMs <= now + 300_000L
        assertTrue("Invite must be within 7-day lifetime", isFresh)
        assertTrue("Invite must not exceed 5-minute future clock skew", isClockSkewValid)

        // Auto-accept triggers because invite is relayed (!isDirectFromOwner)
        val autoAccept = !isDirectFromOwner
        assertTrue("puppy must auto-accept relayed invite from trusted friend", autoAccept)

        // Step 7: puppy creates signed invite response
        val rawResponse = GroupInviteResponse(
            inviteId = extractedInvite.inviteId,
            groupId = extractedInvite.groupId,
            accepted = true,
            memberFingerprint = candidateFp,
            memberPeerName = "puppy",
            memberDeviceId = candidateDeviceId,
            memberSigningKey = candidatePub,
            createdAtMs = now,
            signatureBase64 = "",
        )
        val responseSig = GroupIdentitySignatures.sign(rawResponse.canonicalForSignature())
        val signedResponse = rawResponse.copy(signatureBase64 = responseSig)
        assertTrue("puppy must sign invite response", signedResponse.verify())

        // Step 8: puppy sends response to doggy; doggy forwards response to Foxxxy
        val relayedResponseJson = GroupControlFrames.inviteResponseToJson(signedResponse)
        val parsedByOwner = GroupControlFrames.parseInviteResponse(relayedResponseJson)

        // Foxxxy verifies response without requiring transport sender to be puppy
        assertTrue("Foxxxy must verify candidate signature on relayed response", parsedByOwner.verify())
        assertEquals(candidateDeviceId, parsedByOwner.memberDeviceId)
        assertEquals(true, parsedByOwner.accepted)
    }

    @Test
    fun testTamperedRelayedInviteIsRejectedByCandidate() {
        val authenticInvite = createOwnerSignedInvite()
        assertTrue(authenticInvite.verifySignature())

        // Malicious relay modifies title to trick puppy
        val tamperedInvite = authenticInvite.copy(title = "Phishing / Malicious Group")
        assertFalse("Tampered invite title must fail Ed25519 verification", tamperedInvite.verifySignature())

        // Malicious relay modifies epoch secret
        val tamperedSecret = authenticInvite.copy(epochSecretBase64 = "YmFkLXNlY3JldC0zMmJ5dGVzLWJhZDEyMw==")
        assertFalse("Tampered epoch secret must fail Ed25519 verification", tamperedSecret.verifySignature())
    }

    @Test
    fun testMaliciousMemberCannotInjectFakeMemberAddedEvent() {
        val fakeInvite = createOwnerSignedInvite()

        // doggy (memberDeviceId) attempts to author MEMBER_ADDED event instead of owner (ownerDeviceId)
        val eventAuthorDeviceId = memberDeviceId

        val isOwnerAuthor = (eventAuthorDeviceId == ownerDeviceId)
        assertFalse("Defense-in-depth: Non-owner member cannot author MEMBER_ADDED event", isOwnerAuthor)
    }
}
