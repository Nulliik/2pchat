package com.example.twopchat.group.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupCryptoInstrumentedTest {
    @Test
    fun aesGcmRoundTripAuthenticatesBinaryPayloadAndUsesFreshNonce() {
        val epochSecret = EpochAeadGroupCrypto.generateEpochSecret()
        val aad = "group-1|epoch-7|event-42".toByteArray()
        val plaintext = ByteArray(128 * 1024 + 37) { index ->
            ((index * 31 + 17) and 0xff).toByte()
        }

        val first = EpochAeadGroupCrypto.protect(epochSecret, aad, plaintext)
        val second = EpochAeadGroupCrypto.protect(epochSecret, aad, plaintext)

        assertEquals(32, epochSecret.size)
        assertFalse(first.nonceBase64 == second.nonceBase64)
        assertFalse(first.ciphertextBase64 == second.ciphertextBase64)
        assertArrayEquals(
            plaintext,
            EpochAeadGroupCrypto.unprotect(epochSecret, aad, first),
        )
        assertArrayEquals(
            plaintext,
            EpochAeadGroupCrypto.unprotect(epochSecret, aad, second),
        )
    }

    @Test
    fun ciphertextTamperIsRejected() {
        val secret = EpochAeadGroupCrypto.generateEpochSecret()
        val aad = "group|epoch-1".toByteArray()
        val protected = EpochAeadGroupCrypto.protect(secret, aad, "secret message".toByteArray())
        val ciphertext = Base64.decode(protected.ciphertextBase64, Base64.NO_WRAP)
        ciphertext[ciphertext.lastIndex / 2] =
            (ciphertext[ciphertext.lastIndex / 2].toInt() xor 0x40).toByte()
        val tampered = protected.copy(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )

        assertThrows(SecurityException::class.java) {
            EpochAeadGroupCrypto.unprotect(secret, aad, tampered)
        }
    }

    @Test
    fun aadAndEpochSecretAreCryptographicBoundaries() {
        val epochSevenSecret = EpochAeadGroupCrypto.generateEpochSecret()
        val epochEightSecret = EpochAeadGroupCrypto.generateEpochSecret()
        val epochSevenAad = "group-1|epoch-7|message".toByteArray()
        val payload = EpochAeadGroupCrypto.protect(
            epochSevenSecret,
            epochSevenAad,
            "epoch-bound plaintext".toByteArray(),
        )

        assertThrows(SecurityException::class.java) {
            EpochAeadGroupCrypto.unprotect(
                epochSevenSecret,
                "group-1|epoch-8|message".toByteArray(),
                payload,
            )
        }
        assertThrows(SecurityException::class.java) {
            EpochAeadGroupCrypto.unprotect(epochEightSecret, epochSevenAad, payload)
        }
    }

    @Test
    fun malformedNonceAndKeyLengthAreRejected() {
        val secret = EpochAeadGroupCrypto.generateEpochSecret()
        val payload = EpochAeadGroupCrypto.protect(secret, byteArrayOf(1), byteArrayOf(2))
        val shortNonce = payload.copy(
            nonceBase64 = Base64.encodeToString(ByteArray(11), Base64.NO_WRAP),
        )

        assertThrows(SecurityException::class.java) {
            EpochAeadGroupCrypto.unprotect(secret, byteArrayOf(1), shortNonce)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpochAeadGroupCrypto.protect(ByteArray(31), byteArrayOf(), byteArrayOf())
        }
    }
}
