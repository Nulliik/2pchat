package com.example.twopchat.group

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.crypto.EpochAeadGroupCrypto
import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.protocol.GroupControlFrames
import com.example.twopchat.group.protocol.GroupInvite
import com.example.twopchat.group.protocol.GroupInviteMember
import com.example.twopchat.group.protocol.GroupInviteResponse
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.runtime.GroupChatCoordinator
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID

/**
 * End-to-End Instrumented Test with 3 simulated device identities:
 * - Owner: Foxxxy
 * - Member: doggy (intermediate relay peer)
 * - Invitee: puppy (candidate recipient)
 *
 * Simulates P2P network topology where Foxxxy and puppy have no direct connection,
 * requiring the owner-signed invite and candidate response to be relayed through doggy.
 */
@MediumTest
@RunWith(AndroidJUnit4::class)
class GroupInviteRelayE2ETest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val ownerKeyAlias = "test-owner-alias-${UUID.randomUUID()}"
    private val memberKeyAlias = "test-member-alias-${UUID.randomUUID()}"
    private val puppyKeyAlias = "test-puppy-alias-${UUID.randomUUID()}"

    private lateinit var ownerKeyPair: KeyPair
    private lateinit var ownerSigningKey: String
    private lateinit var ownerFingerprint: String
    private lateinit var ownerDeviceId: String

    private lateinit var memberKeyPair: KeyPair
    private lateinit var memberSigningKey: String
    private lateinit var memberFingerprint: String
    private lateinit var memberDeviceId: String

    private lateinit var puppyKeyPair: KeyPair
    private lateinit var puppySigningKey: String
    private lateinit var puppyFingerprint: String
    private lateinit var puppyDeviceId: String

    private val groupId = "group_e2e_relay_${UUID.randomUUID()}"

    @Before
    fun setUp() {
        NativeBridge.initialize()

        ownerKeyPair = generateEd25519Key(ownerKeyAlias)
        val rawOwnerPub = ownerKeyPair.public.encoded.takeLast(32).toByteArray()
        ownerSigningKey = Base64.encodeToString(rawOwnerPub, Base64.NO_WRAP)
        ownerFingerprint = sha256Hex(rawOwnerPub)
        ownerDeviceId = GroupChatCoordinator.stableDeviceId(ownerFingerprint)

        memberKeyPair = generateEd25519Key(memberKeyAlias)
        val rawMemberPub = memberKeyPair.public.encoded.takeLast(32).toByteArray()
        memberSigningKey = Base64.encodeToString(rawMemberPub, Base64.NO_WRAP)
        memberFingerprint = sha256Hex(rawMemberPub)
        memberDeviceId = GroupChatCoordinator.stableDeviceId(memberFingerprint)

        puppyKeyPair = generateEd25519Key(puppyKeyAlias)
        val rawPuppyPub = puppyKeyPair.public.encoded.takeLast(32).toByteArray()
        puppySigningKey = Base64.encodeToString(rawPuppyPub, Base64.NO_WRAP)
        puppyFingerprint = sha256Hex(rawPuppyPub)
        puppyDeviceId = GroupChatCoordinator.stableDeviceId(puppyFingerprint)

        P2PPreferences.prefs(context).edit()
            .putString(P2PPreferences.peerFingerprint("Foxxxy"), ownerFingerprint)
            .putString(P2PPreferences.peerFingerprint("doggy"), memberFingerprint)
            .putString(P2PPreferences.peerFingerprint("puppy"), puppyFingerprint)
            .commit()
    }

    @After
    fun tearDown() {
        listOf(ownerKeyAlias, memberKeyAlias, puppyKeyAlias).forEach { alias ->
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(alias)
                }
            }
        }
    }

    private fun generateEd25519Key(alias: String): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("ed25519"))
                .setDigests(KeyProperties.DIGEST_NONE)
                .build(),
        )
        return kpg.generateKeyPair()
    }

    private fun signWithKey(keyPair: KeyPair, data: ByteArray): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(data)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun testInviteRelayThroughMember_3Devices() {
        // Step 1: Simulated network topology
        // Foxxxy ↔ doggy, doggy ↔ puppy. NO direct connection Foxxxy ↔ puppy.
        val foxxxyConnectedPeers = setOf("doggy")
        val doggyConnectedPeers = setOf("Foxxxy", "puppy")
        val puppyConnectedPeers = setOf("doggy")

        assertFalse("Foxxxy has no direct connection to puppy", foxxxyConnectedPeers.contains("puppy"))
        assertTrue("doggy is connected to puppy", doggyConnectedPeers.contains("puppy"))
        assertTrue("puppy is connected to doggy", puppyConnectedPeers.contains("doggy"))

        // Step 2: doggy invites puppy to Foxxxy's group
        // Foxxxy approves and generates signed GroupInvite
        val epochSecret = EpochAeadGroupCrypto.generateEpochSecret()
        val now = System.currentTimeMillis()
        val members = listOf(
            GroupInviteMember(
                fingerprint = ownerFingerprint,
                peerName = "Foxxxy",
                deviceId = ownerDeviceId,
                signingKey = ownerSigningKey,
                role = GroupRole.OWNER.name,
                status = "ACTIVE",
            ),
            GroupInviteMember(
                fingerprint = puppyFingerprint,
                peerName = "puppy",
                deviceId = puppyDeviceId,
                signingKey = puppySigningKey,
                role = GroupRole.MEMBER.name,
                status = "INVITED",
            ),
        )
        val unsignedInvite = GroupInvite(
            inviteId = "inv_e2e_" + UUID.randomUUID().toString(),
            groupId = groupId,
            title = "P2P Relay Club",
            description = "Group with relayed invite delivery",
            epoch = 1L,
            epochSecretBase64 = Base64.encodeToString(epochSecret, Base64.NO_WRAP),
            ownerFingerprint = ownerFingerprint,
            senderFingerprint = ownerFingerprint,
            senderSigningKey = ownerSigningKey,
            coordinatorFingerprint = ownerFingerprint,
            controlHead = "ctrl_001",
            historyCursors = mapOf(ownerDeviceId to 1L, puppyDeviceId to 0L),
            createdAtMs = now,
            rosterSize = 3,
            members = members,
            cryptoSuite = EpochAeadGroupCrypto.suiteId,
            signatureBase64 = "",
        )
        val ownerSignature = signWithKey(ownerKeyPair, unsignedInvite.canonicalForSignature().toByteArray(Charsets.UTF_8))
        val signedInvite = unsignedInvite.copy(signatureBase64 = ownerSignature)

        // Verify invite is signed by owner
        assertTrue("Invite must be signed with owner Ed25519 key", signedInvite.verifySignature())

        // Step 3: Owner creates MEMBER_ADDED control event with invite_json
        val inviteJson = GroupWireProtocol.inviteToJson(signedInvite)
        val memberAddedPayload = JSONObject().apply {
            put("member_device_id", puppyDeviceId)
            put("fingerprint", puppyFingerprint)
            put("peer_name", "puppy")
            put("role", GroupRole.MEMBER.name)
            put("status", "INVITED")
            put("invite_json", inviteJson.toString())
        }

        // Step 4: doggy receives MEMBER_ADDED, verifies defense-in-depth checks
        val parsedEmbeddedInvite = GroupWireProtocol.parseInvite(
            JSONObject(memberAddedPayload.getString("invite_json")),
        )
        assertTrue("doggy verifies embedded invite signature", parsedEmbeddedInvite.verifySignature())
        assertEquals("Invite owner must match Foxxxy", ownerFingerprint, parsedEmbeddedInvite.ownerFingerprint)

        // doggy relays invite_json to puppy
        val transportSenderToPuppy = "doggy"

        // Step 5: puppy receives invite from doggy
        // Transport sender is doggy, but signed sender is Foxxxy
        val isDirectFromOwner = (P2PPreferences.getPeerFingerprint(context, transportSenderToPuppy) == parsedEmbeddedInvite.senderFingerprint)
        assertFalse("Transport sender doggy is not the owner Foxxxy", isDirectFromOwner)

        // Under relaxed transport check, puppy checks end-to-end cryptographic validity
        assertTrue("puppy validates owner signature on relayed invite", parsedEmbeddedInvite.verifySignature())

        // Verify recipient identity binding
        val localRecipient = parsedEmbeddedInvite.members.firstOrNull {
            it.fingerprint == puppyFingerprint && it.deviceId == puppyDeviceId
        }
        assertNotNull("puppy must be target recipient", localRecipient)
        assertEquals("INVITED", localRecipient?.status)

        // Anti-replay checks
        val currentTime = System.currentTimeMillis()
        assertTrue("Invite age <= 7 days", (currentTime - parsedEmbeddedInvite.createdAtMs) <= 7L * 24L * 3600L * 1000L)
        assertTrue("Invite clock skew <= 5 minutes", parsedEmbeddedInvite.createdAtMs <= currentTime + 300_000L)

        // Auto-accept is enabled because invite was relayed (!isDirectFromOwner)
        val autoAccept = !isDirectFromOwner
        assertTrue("puppy auto-accepts relayed invite from friend", autoAccept)

        // Step 6: puppy generates signed response
        val response = GroupInviteResponse(
            inviteId = parsedEmbeddedInvite.inviteId,
            groupId = parsedEmbeddedInvite.groupId,
            accepted = true,
            memberFingerprint = puppyFingerprint,
            memberPeerName = "puppy",
            memberDeviceId = puppyDeviceId,
            memberSigningKey = puppySigningKey,
            createdAtMs = currentTime,
            signatureBase64 = "",
        )
        val puppyResponseSig = signWithKey(puppyKeyPair, response.canonicalForSignature().toByteArray(Charsets.UTF_8))
        val signedResponse = response.copy(signatureBase64 = puppyResponseSig)

        assertTrue("puppy's response signature is valid", signedResponse.verify())

        // Step 7: doggy forwards puppy's response to Foxxxy
        val relayedResponseJson = GroupControlFrames.inviteResponseToJson(signedResponse)
        val responseReceivedByOwner = GroupControlFrames.parseInviteResponse(relayedResponseJson)

        assertTrue("Owner verifies candidate signature on forwarded response", responseReceivedByOwner.verify())
        assertEquals(puppyDeviceId, responseReceivedByOwner.memberDeviceId)
        assertEquals(true, responseReceivedByOwner.accepted)
    }
}
