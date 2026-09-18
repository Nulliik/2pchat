package com.example.twopchat

import com.example.twopchat.security.SecureStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureStorageSafetyTest {

    @Test
    fun testDecryptBytesInvalidPayloadGracefulFallback() {
        // A truncated or non-version-matching byte array should safely return the original bytes without throwing
        val invalidPayload = byteArrayOf(0x99.toByte(), 1, 2, 3)
        val result = SecureStorage.decryptBytes(invalidPayload)
        assertArrayEquals(invalidPayload, result)
    }

    @Test
    fun testDecryptBytesEmptyArrayGracefulFallback() {
        val emptyPayload = ByteArray(0)
        val result = SecureStorage.decryptBytes(emptyPayload)
        assertArrayEquals(emptyPayload, result)
    }

    @Test
    fun testDecryptInvalidStringGracefulFallback() {
        val plainText = "Plain unencrypted text"
        val result = SecureStorage.decrypt(plainText)
        assertEquals(plainText, result)
    }

    @Test
    fun testDecryptEmptyStringGracefulFallback() {
        val emptyText = ""
        val result = SecureStorage.decrypt(emptyText)
        assertEquals("", result)
    }

    @Test
    fun testDecryptNullReturnsNull() {
        val result = SecureStorage.decrypt(null)
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun testEncryptFailsClosedWhenKeystoreUnavailableAndRoundTripsOtherwise() {
        val plain = "Hello world cached string"
        val encrypted = try {
            SecureStorage.encrypt(plain)
        } catch (e: Exception) {
            // BUG-17: no plaintext fallback on failure; JVM has no AndroidKeyStore.
            // KeyStoreException/NoSuchAlgorithmException are the expected JVM absence paths.
            org.junit.Assert.assertTrue(
                "encrypt must fail closed with a keystore error, not silently pass",
                e is java.security.KeyStoreException || e.cause is java.security.NoSuchAlgorithmException,
            )
            null
        } ?: return
        org.junit.Assert.assertTrue(SecureStorage.isEncrypted(encrypted))
        org.junit.Assert.assertNotEquals(plain, encrypted)
        val decryptedFirst = SecureStorage.decrypt(encrypted)
        org.junit.Assert.assertEquals(plain, decryptedFirst)
        val decryptedSecond = SecureStorage.decrypt(encrypted)
        org.junit.Assert.assertEquals(plain, decryptedSecond)
    }

    @Test
    fun testDecryptBytesTruncatedPayloadGracefulFallback() {
        // A truncated envelope with BINARY_VERSION header (size < 29) must return original bytes without throwing
        val truncatedPayload = byteArrayOf(0x01.toByte(), 1, 2, 3, 4)
        val result = SecureStorage.decryptBytes(truncatedPayload)
        assertArrayEquals(truncatedPayload, result)
    }

    @Test
    fun testDecryptCorruptedEnvelopeGracefulFallback() {
        // A corrupted envelope starting with enc: must return null without throwing IllegalStateException
        val corruptedEnvelope = "enc:v1:corrupted_invalid_base64_or_truncated"
        val result = SecureStorage.decrypt(corruptedEnvelope)
        org.junit.Assert.assertNull(result)
    }
}

