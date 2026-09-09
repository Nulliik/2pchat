package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.security.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class PassphraseSecurityTest {

    @Test
    fun testZeroizeByteArrayFillsZeros() {
        val sampleKey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val expectedZeros = ByteArray(8) { 0 }

        SecurityUtils.zeroize(sampleKey)

        assertTrue(Arrays.equals(expectedZeros, sampleKey))
    }

    @Test
    fun testZeroizeCharArrayFillsNullChars() {
        val sampleChars = charArray5Of('a', 'b', 'c', 'd', 'e')
        val expectedNullChars = CharArray(5) { '\u0000' }

        SecurityUtils.zeroize(sampleChars)

        assertTrue(Arrays.equals(expectedNullChars, sampleChars))
    }

    @Test
    fun testSecureZeroizationOfByteArrayWithArraysFill() {
        val sensitivePassphraseBytes = "my-secret-db-passphrase-12345".toByteArray(Charsets.UTF_8)
        val copy = sensitivePassphraseBytes.clone()
        assertTrue(Arrays.equals(copy, sensitivePassphraseBytes))

        SecurityUtils.zeroize(sensitivePassphraseBytes)

        for (b in sensitivePassphraseBytes) {
            assertEquals(0.toByte(), b)
        }
        assertFalse(Arrays.equals(copy, sensitivePassphraseBytes))
    }

    private fun charArray5Of(c1: Char, c2: Char, c3: Char, c4: Char, c5: Char): CharArray {
        return charArrayOf(c1, c2, c3, c4, c5)
    }
}
