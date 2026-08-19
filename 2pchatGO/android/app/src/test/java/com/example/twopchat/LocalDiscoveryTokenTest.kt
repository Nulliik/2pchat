package com.example.twopchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiscoveryTokenTest {

    @Test
    fun testTokenDerivationIsDeterministicForSameDay() {
        val fp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val token1 = LocalDiscoveryToken.deriveToken(fp, epochDay = 20500L)
        val token2 = LocalDiscoveryToken.deriveToken(fp, epochDay = 20500L)

        assertTrue(token1.isNotBlank())
        assertTrue(token1.length <= 32)
        assertTrue(token1 == token2)
    }

    @Test
    fun testTokenRotatesOnDifferentDays() {
        val fp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val tokenDay1 = LocalDiscoveryToken.deriveToken(fp, epochDay = 20500L)
        val tokenDay2 = LocalDiscoveryToken.deriveToken(fp, epochDay = 20501L)

        assertNotEquals(tokenDay1, tokenDay2)
    }

    @Test
    fun testMatchesFingerprintWithCurrentAndPreviousDay() {
        val fp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val nowMs = 20500L * 86_400_000L + 1000L

        val todayToken = LocalDiscoveryToken.deriveToken(fp, epochDay = 20500L)
        val yesterdayToken = LocalDiscoveryToken.deriveToken(fp, epochDay = 20499L)
        val futureToken = LocalDiscoveryToken.deriveToken(fp, epochDay = 20505L)

        assertTrue(LocalDiscoveryToken.matchesFingerprint(todayToken, fp, nowMs))
        assertTrue(LocalDiscoveryToken.matchesFingerprint(yesterdayToken, fp, nowMs))
        assertFalse(LocalDiscoveryToken.matchesFingerprint(futureToken, fp, nowMs))
        assertFalse(LocalDiscoveryToken.matchesFingerprint("random_token", fp, nowMs))
    }
}
