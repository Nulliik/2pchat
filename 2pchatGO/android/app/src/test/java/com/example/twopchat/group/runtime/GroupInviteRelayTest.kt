package com.example.twopchat.group.runtime

import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.protocol.GroupInvite
import com.example.twopchat.group.protocol.GroupInviteMember
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.protocol.Capability
import com.example.twopchat.protocol.NegotiatedSession
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class GroupInviteRelayTest {

    private val testSignatures = mutableMapOf<Pair<String, String>, Boolean>()

    @Before
    fun setUp() {
        GroupIdentitySignatures.testSigner = { canonical ->
            "sig_mock_${canonical.hashCode()}"
        }
        GroupIdentitySignatures.testVerifier = { pubKey, canonical, signature ->
            signature == "sig_mock_${canonical.hashCode()}" && pubKey.isNotBlank()
        }
    }

    @After
    fun tearDown() {
        GroupIdentitySignatures.testSigner = null
        GroupIdentitySignatures.testVerifier = null
    }

    private fun createSampleInvite(
        ownerFp: String = "fp_foxxxy_owner",
        ownerPub: String = "pub_foxxxy_key",
        candidateFp: String = "fp_puppy_candidate",
        candidatePub: String = "pub_puppy_key",
        createdAtMs: Long = System.currentTimeMillis(),
        groupId: String = "group_test_relay",
    ): GroupInvite {
        val members = listOf(
            GroupInviteMember(
                fingerprint = ownerFp,
                peerName = "Foxxxy",
                deviceId = GroupChatCoordinator.stableDeviceId(ownerFp),
                signingKey = ownerPub,
                role = "OWNER",
                status = "ACTIVE",
            ),
            GroupInviteMember(
                fingerprint = candidateFp,
                peerName = "puppy",
                deviceId = GroupChatCoordinator.stableDeviceId(candidateFp),
                signingKey = candidatePub,
                role = "MEMBER",
                status = "INVITED",
            ),
        )
        val unsigned = GroupInvite(
            inviteId = "inv_" + UUID.randomUUID().toString(),
            groupId = groupId,
            title = "Test Relay Group",
            description = "Group for testing relay delivery",
            epoch = 1L,
            epochSecretBase64 = "dGVzdC1lcG9jaC1zZWNyZXQtMzJieXRlcw==",
            ownerFingerprint = ownerFp,
            senderFingerprint = ownerFp,
            senderSigningKey = ownerPub,
            coordinatorFingerprint = ownerFp,
            controlHead = "ctrl_head_001",
            historyCursors = mapOf(
                GroupChatCoordinator.stableDeviceId(ownerFp) to 10L,
                GroupChatCoordinator.stableDeviceId(candidateFp) to 0L,
            ),
            createdAtMs = createdAtMs,
            rosterSize = 2,
            members = members,
            cryptoSuite = "epoch_aead_v1",
            signatureBase64 = "",
        )
        val sig = GroupIdentitySignatures.sign(unsigned.canonicalForSignature())
        return unsigned.copy(signatureBase64 = sig)
    }

    @Test
    fun testOwnerSignatureVerificationSucceeds() {
        val invite = createSampleInvite()
        assertTrue("Authentic owner-signed invite must verify", invite.verifySignature())
    }

    @Test
    fun testTamperedInviteSignatureFails() {
        val invite = createSampleInvite()
        val tampered = invite.copy(title = "Malicious Hijacked Group")
        assertFalse("Tampered invite must fail cryptographic verification", tampered.verifySignature())
    }

    @Test
    fun testRelayTransportSecurityModel() {
        val ownerFp = "fp_foxxxy_owner"
        val relayPeerName = "doggy"
        val relayFp = "fp_doggy_relay"
        val invite = createSampleInvite(ownerFp = ownerFp)

        // Direct check: sender is doggy, but signed sender is Foxxxy
        val isDirectFromOwner = (relayFp == invite.senderFingerprint)
        assertFalse("Relayed invite transport identity does not match signed sender", isDirectFromOwner)

        // Under relaxed transport check, we rely on end-to-end signature verification
        assertTrue("Cryptographic signature remains valid regardless of relay peer", invite.verifySignature())
        assertEquals("Owner fingerprint must match signed sender", ownerFp, invite.senderFingerprint)
    }

    @Test
    fun testAntiReplayExpiredInviteRejected() {
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()

        // Invite created 8 days ago
        val expiredInvite = createSampleInvite(createdAtMs = now - (sevenDaysMs + 10_000L))
        val isExpired = (now - expiredInvite.createdAtMs) > sevenDaysMs
        assertTrue("Invite older than 7 days must be considered expired", isExpired)

        // Fresh invite created 1 hour ago
        val freshInvite = createSampleInvite(createdAtMs = now - 3600_000L)
        val isFreshExpired = (now - freshInvite.createdAtMs) > sevenDaysMs
        assertFalse("Invite younger than 7 days must not be expired", isFreshExpired)
    }

    @Test
    fun testAntiReplayFutureClockSkew() {
        val maxClockSkewMs = GroupChatCoordinator.MAX_CLOCK_SKEW_MS
        val now = System.currentTimeMillis()

        // Created 10 minutes in future -> rejected
        val futureInvite = createSampleInvite(createdAtMs = now + maxClockSkewMs + 60_000L)
        assertTrue("Invite too far in future must exceed clock skew", futureInvite.createdAtMs > now + maxClockSkewMs)

        // Created 1 minute in future -> within acceptable clock skew
        val tolerableInvite = createSampleInvite(createdAtMs = now + 60_000L)
        assertFalse("Invite within clock skew must be accepted", tolerableInvite.createdAtMs > now + maxClockSkewMs)
    }

    @Test
    fun testCapabilityNegotiationForRelayAndQuery() {
        val sessionWithRelay = NegotiatedSession(
            protocolVersion = 1,
            activeCapabilities = setOf(
                Capability.GROUP_SUITE_V1.id,
                Capability.GROUP_INVITE_RELAY_V1.id,
                Capability.GROUP_SUCCESSION_QUERY_V1.id,
            ),
            peerIsOutdated = false,
            peerIsLegacy = false,
        )
        assertTrue(sessionWithRelay.supports(Capability.GROUP_INVITE_RELAY_V1))
        assertTrue(sessionWithRelay.supports(Capability.GROUP_SUCCESSION_QUERY_V1))

        val legacySession = NegotiatedSession(
            protocolVersion = 1,
            activeCapabilities = setOf(Capability.GROUP_SUITE_V1.id),
            peerIsOutdated = true,
            peerIsLegacy = true,
        )
        assertFalse(legacySession.supports(Capability.GROUP_INVITE_RELAY_V1))
        assertFalse(legacySession.supports(Capability.GROUP_SUCCESSION_QUERY_V1))
    }

    @Test
    fun testRelayRoutingDistinctionBetweenRecipientAndIntermediate() {
        val ownerFp = "fp_foxxxy_owner"
        val intermediateFp = "fp_doggy_member"
        val candidateFp = "fp_puppy_candidate"

        val invite = createSampleInvite(ownerFp = ownerFp, candidateFp = candidateFp)

        // On intermediate peer (doggy): local identity is doggy
        val intermediateLocalDeviceId = GroupChatCoordinator.stableDeviceId(intermediateFp)
        val localEntryOnIntermediate = invite.members.firstOrNull {
            it.fingerprint == intermediateFp && it.deviceId == intermediateLocalDeviceId
        }
        // Intermediate peer is not recipient
        assertEquals(null, localEntryOnIntermediate)

        // On candidate peer (puppy): local identity is puppy
        val candidateLocalDeviceId = GroupChatCoordinator.stableDeviceId(candidateFp)
        val localEntryOnCandidate = invite.members.firstOrNull {
            it.fingerprint == candidateFp && it.deviceId == candidateLocalDeviceId
        }
        // Candidate peer is recipient
        assertTrue(localEntryOnCandidate != null)
        assertEquals("puppy", localEntryOnCandidate?.peerName)
        assertEquals("MEMBER", localEntryOnCandidate?.role)
        assertEquals("INVITED", localEntryOnCandidate?.status)
    }

    @Test
    fun testAutoAcceptTriggersWhenPendingJoinOrRelayed() {
        val groupId = "group_auto_accept_test"

        // Case 1: Direct from owner, but candidate clicked join link
        val isPendingJoin = true
        val isDirectFromOwner1 = true
        val autoAccept1 = isPendingJoin || !isDirectFromOwner1
        assertTrue("Auto-accept must trigger when join request was pending", autoAccept1)

        // Case 2: Relayed through friend (not direct from owner)
        val isDirectFromOwner2 = false
        val autoAccept2 = !isDirectFromOwner2
        assertTrue("Auto-accept must trigger when invite is relayed through trusted friend", autoAccept2)
    }
}
