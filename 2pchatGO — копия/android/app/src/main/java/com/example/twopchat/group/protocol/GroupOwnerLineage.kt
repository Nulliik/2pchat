package com.example.twopchat.group.protocol

import com.example.twopchat.group.crypto.GroupIdentitySignatures
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A portable delegation from one group owner credential to the next.
 *
 * The certificate id is placed in the clear, signed target of the matching
 * OWNERSHIP_TRANSFERRED event. The certificate itself is kept in the encrypted
 * event body and can later be carried by an invite, allowing a returning member
 * to verify owner continuity without possessing epochs created while it was
 * absent.
 */
data class GroupOwnerTransitionCertificate(
    val groupId: String,
    val previousOwnerAnchor: String,
    val lineageSequence: Int,
    val previousControlHead: String?,
    val oldOwnerFingerprint: String,
    val oldOwnerDeviceId: String,
    val oldOwnerSigningKey: String,
    val newOwnerFingerprint: String,
    val newOwnerDeviceId: String,
    val newOwnerSigningKey: String,
    val createdAtMs: Long,
    val nonce: String,
    val signatureBase64: String,
) {
    fun canonicalForSignature(): String = buildString {
        append("2pchat-group-owner-transition-v1\n")
        append(GroupWireProtocol.VERSION).append('\n')
        appendCanonical(groupId)
        appendCanonical(previousOwnerAnchor)
        append(lineageSequence).append('\n')
        appendCanonical(previousControlHead.orEmpty())
        appendCanonical(oldOwnerFingerprint)
        appendCanonical(oldOwnerDeviceId)
        appendCanonical(oldOwnerSigningKey)
        appendCanonical(newOwnerFingerprint)
        appendCanonical(newOwnerDeviceId)
        appendCanonical(newOwnerSigningKey)
        append(createdAtMs).append('\n')
        appendCanonical(nonce)
    }

    fun verify(): Boolean =
        GroupIdentitySignatures.verify(
            oldOwnerSigningKey,
            canonicalForSignature(),
            signatureBase64,
        )

    fun transitionId(): String = ownerLineageSha256(
        "2pchat-group-owner-transition-id-v1\u0000" +
            canonicalForSignature() +
            "\u0000" +
            signatureBase64,
    )
}

object GroupOwnerLineage {
    const val MAX_TRANSITIONS = 128

    fun rootAnchor(
        groupId: String,
        ownerFingerprint: String,
        ownerDeviceId: String,
        ownerSigningKey: String,
    ): String = ownerLineageSha256(
        buildString {
            append("2pchat-group-owner-root-v1\n")
            append(GroupWireProtocol.VERSION).append('\n')
            appendCanonical(groupId)
            appendCanonical(ownerFingerprint)
            appendCanonical(ownerDeviceId)
            appendCanonical(ownerSigningKey)
        },
    )

    fun nextAnchor(certificate: GroupOwnerTransitionCertificate): String =
        certificate.transitionId()

    fun toJson(certificate: GroupOwnerTransitionCertificate): JSONObject =
        JSONObject().apply {
            put("group_id", certificate.groupId)
            put("previous_owner_anchor", certificate.previousOwnerAnchor)
            put("lineage_sequence", certificate.lineageSequence)
            if (certificate.previousControlHead == null) {
                put("previous_control_head", JSONObject.NULL)
            } else {
                put("previous_control_head", certificate.previousControlHead)
            }
            put("old_owner_fingerprint", certificate.oldOwnerFingerprint)
            put("old_owner_device_id", certificate.oldOwnerDeviceId)
            put("old_owner_signing_key", certificate.oldOwnerSigningKey)
            put("new_owner_fingerprint", certificate.newOwnerFingerprint)
            put("new_owner_device_id", certificate.newOwnerDeviceId)
            put("new_owner_signing_key", certificate.newOwnerSigningKey)
            put("created_at_ms", certificate.createdAtMs)
            put("nonce", certificate.nonce)
            put("signature", certificate.signatureBase64)
        }

    fun parse(json: JSONObject): GroupOwnerTransitionCertificate =
        GroupOwnerTransitionCertificate(
            groupId = json.requiredLineageToken("group_id", 128),
            previousOwnerAnchor = json.requiredLineageToken("previous_owner_anchor", 128),
            lineageSequence = json.optInt("lineage_sequence", -1).also {
                require(it in 1..MAX_TRANSITIONS) { "invalid owner lineage sequence" }
            },
            previousControlHead = json.optionalLineageToken("previous_control_head", 128),
            oldOwnerFingerprint = json.requiredLineageText("old_owner_fingerprint", 256),
            oldOwnerDeviceId = json.requiredLineageToken("old_owner_device_id", 128),
            oldOwnerSigningKey = json.requiredLineageText("old_owner_signing_key", 256),
            newOwnerFingerprint = json.requiredLineageText("new_owner_fingerprint", 256),
            newOwnerDeviceId = json.requiredLineageToken("new_owner_device_id", 128),
            newOwnerSigningKey = json.requiredLineageText("new_owner_signing_key", 256),
            createdAtMs = json.optLong("created_at_ms", -1L).also {
                require(it > 0L) { "invalid owner transition timestamp" }
            },
            nonce = json.requiredLineageToken("nonce", 128),
            signatureBase64 = json.requiredLineageText("signature", 256),
        )
}

private fun StringBuilder.appendCanonical(value: String) {
    append(value.length).append(':').append(value).append('\n')
}

private fun ownerLineageSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun JSONObject.requiredLineageText(key: String, maxLength: Int): String =
    optString(key).also {
        require(it.isNotBlank() && it.length <= maxLength && '\u0000' !in it) {
            "invalid $key"
        }
    }

private fun JSONObject.requiredLineageToken(key: String, maxLength: Int): String =
    requiredLineageText(key, maxLength).also {
        require(it.all { char -> char.isLetterOrDigit() || char in "-_.:=" }) {
            "invalid $key token"
        }
    }

private fun JSONObject.optionalLineageToken(key: String, maxLength: Int): String? =
    if (!has(key) || isNull(key)) {
        null
    } else {
        requiredLineageToken(key, maxLength)
    }
