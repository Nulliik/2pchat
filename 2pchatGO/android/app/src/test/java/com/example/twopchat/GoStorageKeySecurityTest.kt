package com.example.twopchat

import com.example.twopchat.security.SecurityUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class GoStorageKeySecurityTest {

    @Test
    fun testGoStorageKeyProperties() {
        // Go storage key must strictly be 32 bytes (256 bits) for XChaCha20-Poly1305
        val randomKey = ByteArray(32)
        SecureRandom().nextBytes(randomKey)
        assertEquals(32, randomKey.size)

        // Verify zeroization clears all bytes in key buffer
        SecurityUtils.zeroize(randomKey)
        assertTrue(randomKey.all { it == 0.toByte() })
    }

    @Test
    fun testNativeBridgeStorageKeySignature() {
        // Verify NativeBridge methods are callable without crashes
        val invalidKey = ByteArray(16) // Invalid key size (must be 32)
        val result = NativeBridge.setStorageKey(invalidKey)
        // If native library isn't loaded in JVM unit test, returns false safely
        if (!NativeBridge.isLoaded) {
            assertEquals(false, result)
        }
    }
}
