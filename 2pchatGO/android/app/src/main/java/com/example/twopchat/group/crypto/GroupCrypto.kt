package com.example.twopchat.group.crypto

import android.util.Base64
import com.example.twopchat.NativeBridge
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SUITE_V1 = "2pchat-epoch-aes256gcm-ed25519-v1"
const val SUITE_V2 = "2pchat-epoch-aes256gcm-ed25519-v2"

/**
 * Cryptographic boundary used by the group transport.
 *
 * [EpochAeadGroupCrypto] is deliberately named as an epoch AEAD provider and
 * not as MLS.  It keeps the Android group/event/storage implementation useful
 * while a reviewed RFC 9420 implementation is supplied through the same
 * boundary.  Never label this provider as MLS in protocol negotiation.
 */
interface GroupCryptoProvider {
    val suiteId: String
    val supportedSuites: Set<String> get() = setOf(suiteId)

    fun generateEpochSecret(): ByteArray

    fun protect(
        epochSecret: ByteArray,
        authenticatedData: ByteArray,
        plaintext: ByteArray,
    ): ProtectedGroupPayload

    @Throws(SecurityException::class)
    fun unprotect(
        epochSecret: ByteArray,
        authenticatedData: ByteArray,
        payload: ProtectedGroupPayload,
    ): ByteArray
}

data class ProtectedGroupPayload(
    val nonceBase64: String,
    val ciphertextBase64: String,
)

/**
 * AES-256-GCM fallback for the first deployable group transport.
 *
 * Secrets are generated per membership epoch and are distributed only inside
 * existing authenticated Double Ratchet sessions.  This gives authenticated
 * group ciphertext and removal-time re-keying, but it does not claim MLS
 * post-compromise security or TreeKEM semantics.
 */
object EpochAeadGroupCrypto : GroupCryptoProvider {
    override val suiteId: String = SUITE_V1
    override val supportedSuites: Set<String> = setOf(SUITE_V1, SUITE_V2)

    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    override fun generateEpochSecret(): ByteArray =
        ByteArray(KEY_BYTES).also(random::nextBytes)

    override fun protect(
        epochSecret: ByteArray,
        authenticatedData: ByteArray,
        plaintext: ByteArray,
    ): ProtectedGroupPayload {
        require(epochSecret.size == KEY_BYTES) { "group epoch secret must be 32 bytes" }
        if (NativeBridge.isLoaded) {
            val res = NativeBridge.groupEncrypt(epochSecret, authenticatedData, plaintext)
            if (res != null) {
                return ProtectedGroupPayload(nonceBase64 = res.first, ciphertextBase64 = res.second)
            }
        }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(epochSecret.copyOf(), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(authenticatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return ProtectedGroupPayload(
            nonceBase64 = nonce.toBase64(),
            ciphertextBase64 = ciphertext.toBase64(),
        )
    }

    override fun unprotect(
        epochSecret: ByteArray,
        authenticatedData: ByteArray,
        payload: ProtectedGroupPayload,
    ): ByteArray {
        require(epochSecret.size == KEY_BYTES) { "group epoch secret must be 32 bytes" }
        if (NativeBridge.isLoaded) {
            val res = NativeBridge.groupDecrypt(epochSecret, authenticatedData, payload.nonceBase64, payload.ciphertextBase64)
            if (res != null) {
                return res
            }
        }
        val nonce = payload.nonceBase64.decodeBase64Strict()
        val ciphertext = payload.ciphertextBase64.decodeBase64Strict()
        if (nonce.size != NONCE_BYTES) throw SecurityException("invalid group nonce")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(epochSecret.copyOf(), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(authenticatedData)
        return try {
            cipher.doFinal(ciphertext)
        } catch (error: Exception) {
            throw SecurityException("group ciphertext authentication failed", error)
        }
    }
}

/**
 * Ed25519 signatures executed natively via Go lib2pcore.
 */
object GroupIdentitySignatures {
    const val DOMAIN = "2pchat-group-event-signature-v1"

    @Volatile
    var testSigner: ((String) -> String)? = null
    @Volatile
    var testVerifier: ((String, String, String) -> Boolean)? = null

    fun localVerificationKey(): String =
        NativeBridge.getLocalSigningPublicKey()

    fun sign(canonicalPayload: String): String =
        testSigner?.invoke(canonicalPayload) ?: NativeBridge.signGroupPayload(canonicalPayload)

    fun verify(
        verificationKeyBase64: String,
        canonicalPayload: String,
        signatureBase64: String,
    ): Boolean = testVerifier?.invoke(verificationKeyBase64, canonicalPayload, signatureBase64)
        ?: NativeBridge.verifyGroupPayload(
            verificationKeyBase64,
            canonicalPayload,
            signatureBase64,
        )

    fun digestBase64(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).toBase64()
}

internal fun ByteArray.toBase64(): String = try {
    Base64.encodeToString(this, Base64.NO_WRAP) ?: java.util.Base64.getEncoder().encodeToString(this)
} catch (e: Throwable) {
    java.util.Base64.getEncoder().encodeToString(this)
}

internal fun String.decodeBase64Strict(): ByteArray = try {
    val res = Base64.decode(this, Base64.NO_WRAP)
    if (res == null || (res.isEmpty() && this.isNotBlank())) {
        java.util.Base64.getDecoder().decode(this)
    } else {
        res
    }
} catch (error: IllegalArgumentException) {
    try {
        java.util.Base64.getDecoder().decode(this)
    } catch (e: Exception) {
        throw SecurityException("invalid Base64 group field", error)
    }
} catch (e: Throwable) {
    try {
        java.util.Base64.getDecoder().decode(this)
    } catch (err: Exception) {
        throw SecurityException("invalid Base64 group field", e)
    }
}
