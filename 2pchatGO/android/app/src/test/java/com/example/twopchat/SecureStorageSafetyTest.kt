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
}
