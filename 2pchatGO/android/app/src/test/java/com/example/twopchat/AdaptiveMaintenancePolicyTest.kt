package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveMaintenancePolicyTest {

    @Test
    fun testActiveInteractiveModeReturnsTenSeconds() {
        val interval = AdaptiveMaintenancePolicy.computeSessionPollInterval(
            isInteractive = true,
            isDeviceIdleMode = false,
            isPowerSaveMode = false,
            screenOffDurationMs = 0L,
        )
        assertEquals(4_000L, interval)
    }

    @Test
    fun testScreenOffStepOneReturnsThirtySeconds() {
        val interval = AdaptiveMaintenancePolicy.computeSessionPollInterval(
            isInteractive = false,
            isDeviceIdleMode = false,
            isPowerSaveMode = false,
            screenOffDurationMs = 60_000L, // 1 minute
        )
        assertEquals(30_000L, interval)
    }

    @Test
    fun testScreenOffStepTwoReturnsSixtySeconds() {
        val interval = AdaptiveMaintenancePolicy.computeSessionPollInterval(
            isInteractive = false,
            isDeviceIdleMode = false,
            isPowerSaveMode = false,
            screenOffDurationMs = 180_000L, // 3 minutes
        )
        assertEquals(60_000L, interval)
    }

    @Test
    fun testScreenOffStepThreeReturnsOneHundredTwentySeconds() {
        val interval = AdaptiveMaintenancePolicy.computeSessionPollInterval(
            isInteractive = false,
            isDeviceIdleMode = false,
            isPowerSaveMode = false,
            screenOffDurationMs = 600_000L, // 10 minutes
        )
        assertEquals(120_000L, interval)
    }

    @Test
    fun testDeviceIdleModeAlwaysReturnsOneHundredTwentySeconds() {
        val interval = AdaptiveMaintenancePolicy.computeSessionPollInterval(
            isInteractive = true,
            isDeviceIdleMode = true,
            isPowerSaveMode = false,
            screenOffDurationMs = 0L,
        )
        assertEquals(120_000L, interval)
    }

    @Test
    fun testPowerSaveModeForcesBackgroundInterval() {
        val interval = AdaptiveMaintenancePolicy.computeSessionPollInterval(
            isInteractive = true,
            isDeviceIdleMode = false,
            isPowerSaveMode = true,
            screenOffDurationMs = 0L,
        )
        assertEquals(30_000L, interval)
    }

    @Test
    fun testAnnounceIntervalActiveVsBackground() {
        val active = AdaptiveMaintenancePolicy.computeAnnounceInterval(
            isInteractive = true,
            isDeviceIdleMode = false,
        )
        assertEquals(25_000L, active)

        val background = AdaptiveMaintenancePolicy.computeAnnounceInterval(
            isInteractive = false,
            isDeviceIdleMode = false,
        )
        assertEquals(180_000L, background)

        val idle = AdaptiveMaintenancePolicy.computeAnnounceInterval(
            isInteractive = true,
            isDeviceIdleMode = true,
        )
        assertEquals(180_000L, idle)
    }
}
