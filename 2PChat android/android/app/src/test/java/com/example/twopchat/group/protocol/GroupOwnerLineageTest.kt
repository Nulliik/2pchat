package com.example.twopchat.group.protocol

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupOwnerLineageTest {
    @Test
    fun transitionCanonicalFormIsDomainSeparatedAndComplete() {
        val certificate = certificate()

        assertEquals(
            listOf(
                "2pchat-group-owner-transition-v1",
                "1",
                "7:group-1",
                "11:root-anchor",
                "1",
                "14:control-head-7",
                "15:old-fingerprint",
                "10:old-device",
                "15:old-signing-key",
                "15:new-fingerprint",
                "10:new-device",
                "15:new-signing-key",
                "1784000000000",
                "7:nonce-1",
            ).joinToString(separator = "\n", postfix = "\n"),
            certificate.canonicalForSignature(),
        )
    }

    @Test
    fun transitionIdCommitsToCanonicalCertificateAndSignature() {
        val certificate = certificate()
        val expected = sha256(
            "2pchat-group-owner-transition-id-v1\u0000" +
                certificate.canonicalForSignature() +
                "\u0000" +
                certificate.signatureBase64,
        )

        assertEquals(expected, certificate.transitionId())
        assertEquals(64, certificate.transitionId().length)
        assertTrue(certificate.transitionId().all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(
            certificate.transitionId(),
            certificate.copy(newOwnerFingerprint = "other-new-owner").transitionId(),
        )
        assertNotEquals(
            certificate.transitionId(),
            certificate.copy(lineageSequence = 2).transitionId(),
        )
        assertNotEquals(
            certificate.transitionId(),
            certificate.copy(signatureBase64 = "other-signature").transitionId(),
        )
    }

    @Test
    fun lineageRootAndNextAnchorsAreDeterministic() {
        val certificate = certificate()
        val root = GroupOwnerLineage.rootAnchor(
            groupId = certificate.groupId,
            ownerFingerprint = certificate.oldOwnerFingerprint,
            ownerDeviceId = certificate.oldOwnerDeviceId,
            ownerSigningKey = certificate.oldOwnerSigningKey,
        )

        assertEquals(
            root,
            GroupOwnerLineage.rootAnchor(
                groupId = certificate.groupId,
                ownerFingerprint = certificate.oldOwnerFingerprint,
                ownerDeviceId = certificate.oldOwnerDeviceId,
                ownerSigningKey = certificate.oldOwnerSigningKey,
            ),
        )
        assertNotEquals(
            root,
            GroupOwnerLineage.rootAnchor(
                groupId = certificate.groupId,
                ownerFingerprint = "different-owner",
                ownerDeviceId = certificate.oldOwnerDeviceId,
                ownerSigningKey = certificate.oldOwnerSigningKey,
            ),
        )
        assertEquals(certificate.transitionId(), GroupOwnerLineage.nextAnchor(certificate))
    }

    @Test
    fun nullablePreviousControlHeadHasUnambiguousCanonicalEncoding() {
        val withoutHead = certificate().copy(previousControlHead = null)
        val withHead = withoutHead.copy(previousControlHead = "control-head-7")

        assertTrue(withoutHead.canonicalForSignature().contains("0:\n"))
        assertNotEquals(
            withoutHead.canonicalForSignature(),
            withHead.canonicalForSignature(),
        )
    }

    @Test
    fun verifyFailsClosedForMissingVerificationMaterial() {
        assertFalse(
            certificate().copy(
                oldOwnerSigningKey = "",
                signatureBase64 = "",
            ).verify(),
        )
    }

    @Test
    fun inviteSignatureCommitsToOrderedOwnerTransitionChain() {
        val first = certificate()
        val second = certificate().copy(
            previousOwnerAnchor = first.transitionId(),
            lineageSequence = first.lineageSequence + 1,
            previousControlHead = "control-head-8",
            oldOwnerFingerprint = first.newOwnerFingerprint,
            oldOwnerDeviceId = first.newOwnerDeviceId,
            oldOwnerSigningKey = first.newOwnerSigningKey,
            newOwnerFingerprint = "third-fingerprint",
            newOwnerDeviceId = "third-device",
            newOwnerSigningKey = "third-signing-key",
            createdAtMs = first.createdAtMs + 1,
            nonce = "nonce-2",
            signatureBase64 = "signature-2",
        )
        val invite = invite(listOf(first, second))

        assertEquals(listOf(first, second), invite.ownerTransitions)
        assertTrue(invite.canonicalForSignature().contains(first.canonicalForSignature()))
        assertTrue(invite.canonicalForSignature().contains(second.canonicalForSignature()))
        assertNotEquals(
            invite.canonicalForSignature(),
            invite.copy(ownerTransitions = invite.ownerTransitions.reversed())
                .canonicalForSignature(),
        )
        assertNotEquals(
            invite.canonicalForSignature(),
            invite.copy(
                ownerTransitions = listOf(
                    first.copy(signatureBase64 = "substituted-signature"),
                    second,
                ),
            ).canonicalForSignature(),
        )
    }

    @Test
    fun inviteWithoutOwnerTransitionsRetainsBackwardCompatibleDefault() {
        assertTrue(invite().ownerTransitions.isEmpty())
    }

    private fun certificate() = GroupOwnerTransitionCertificate(
        groupId = "group-1",
        previousOwnerAnchor = "root-anchor",
        lineageSequence = 1,
        previousControlHead = "control-head-7",
        oldOwnerFingerprint = "old-fingerprint",
        oldOwnerDeviceId = "old-device",
        oldOwnerSigningKey = "old-signing-key",
        newOwnerFingerprint = "new-fingerprint",
        newOwnerDeviceId = "new-device",
        newOwnerSigningKey = "new-signing-key",
        createdAtMs = 1_784_000_000_000L,
        nonce = "nonce-1",
        signatureBase64 = "signature-1",
    )

    private fun invite(
        transitions: List<GroupOwnerTransitionCertificate> = emptyList(),
    ) = GroupInvite(
        inviteId = "invite-1",
        groupId = "group-1",
        title = "Lineage test",
        description = "",
        epoch = 9L,
        epochSecretBase64 = "epoch-secret",
        ownerFingerprint = transitions.lastOrNull()?.newOwnerFingerprint ?: "old-fingerprint",
        senderFingerprint = "sender-fingerprint",
        senderSigningKey = "sender-signing-key",
        coordinatorFingerprint = "coordinator-fingerprint",
        controlHead = "control-head-9",
        historyCursors = mapOf("old-device" to 7L),
        ownerTransitions = transitions,
        createdAtMs = 1_784_000_000_100L,
        rosterSize = 1,
        members = listOf(
            GroupInviteMember(
                fingerprint = "member-fingerprint",
                peerName = "Member",
                deviceId = "member-device",
                signingKey = "member-signing-key",
                role = "MEMBER",
                status = "ACTIVE",
            ),
        ),
        cryptoSuite = "AES_256_GCM_ED25519_V1",
        signatureBase64 = "invite-signature",
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
