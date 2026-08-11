package com.example.twopchat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PinKeyWrappingTest {

    @Test
    fun testKekDerivationAndKeyWrappingWithCorrectPin() {
        val pin = charArrayOf('1', '2', '3', '4')
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val rawDbPassphrase = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 127, 5, 15, 25)

        val kek = SecurityUtils.deriveKek(pin, salt)
        assertNotNull(kek)

        val wrapped = SecurityUtils.wrapKeyWithKek(kek, rawDbPassphrase)
        assertNotNull(wrapped)

        val unwrapped = SecurityUtils.unwrapKeyWithKek(kek, wrapped!!)
        assertNotNull(unwrapped)
        assertArrayEquals(rawDbPassphrase, unwrapped)

        SecurityUtils.zeroize(kek)
        SecurityUtils.zeroize(pin)
        SecurityUtils.zeroize(unwrapped)
    }

    @Test
    fun testWrongPinFailsUnwrappingWithoutCrash() {
        val pinCorrect = charArrayOf('5', '6', '7', '8')
        val pinWrong = charArrayOf('0', '0', '0', '0')
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val rawDbPassphrase = byteArrayOf(11, 22, 33, 44, 55, 66, 77, 88)

        val kekCorrect = SecurityUtils.deriveKek(pinCorrect, salt)
        val kekWrong = SecurityUtils.deriveKek(pinWrong, salt)

        val wrapped = SecurityUtils.wrapKeyWithKek(kekCorrect, rawDbPassphrase)
        assertNotNull(wrapped)

        val failedUnwrap = SecurityUtils.unwrapKeyWithKek(kekWrong, wrapped!!)
        assertNull(failedUnwrap)

        SecurityUtils.zeroize(kekCorrect)
        SecurityUtils.zeroize(kekWrong)
    }
}
