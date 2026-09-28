package com.example.twopchat.yggdrasil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YggdrasilProxyRecoveryTest {
    @Test
    fun repeatedDegradedProbesTriggerCooldownLimitedRestart() {
        assertFalse(shouldRestartDegradedProxy(1, 60_000L, 0L))
        assertTrue(shouldRestartDegradedProxy(2, 120_000L, 0L))
        assertFalse(shouldRestartDegradedProxy(2, 180_000L, 120_000L))
        assertTrue(shouldRestartDegradedProxy(2, 420_000L, 120_000L))
        assertFalse(shouldRestartDegradedProxy(0, 480_000L, 120_000L))
    }
}
