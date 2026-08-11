package com.example.twopchat

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

    private fun charArray5Of(c1: Char, c2: Char, c3: Char, c4: Char, c5: Char): CharArray {
        return charArrayOf(c1, c2, c3, c4, c5)
    }
}
