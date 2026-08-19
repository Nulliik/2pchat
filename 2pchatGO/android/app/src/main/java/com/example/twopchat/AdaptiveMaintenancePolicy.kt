package com.example.twopchat

/**
 * Pure policy for calculating energy-efficient adaptive polling and discovery intervals
 * based on device screen state, Android Doze mode, and power save state.
 */
object AdaptiveMaintenancePolicy {
    const val ACTIVE_SESSION_POLL_INTERVAL_MS = 10_000L
    const val SCREEN_OFF_STEP1_INTERVAL_MS = 30_000L      // 0 - 2 mins screen off
    const val SCREEN_OFF_STEP2_INTERVAL_MS = 60_000L      // 2 - 5 mins screen off
    const val DEEP_DOZE_INTERVAL_MS = 120_000L            // > 5 mins screen off or Doze mode

    const val ACTIVE_ANNOUNCE_INTERVAL_MS = 25_000L
    const val BACKGROUND_ANNOUNCE_INTERVAL_MS = 180_000L  // 3 mins when screen is off

    const val STEP1_THRESHOLD_MS = 2 * 60 * 1000L
    const val STEP2_THRESHOLD_MS = 5 * 60 * 1000L

    fun computeSessionPollInterval(
        isInteractive: Boolean,
        isDeviceIdleMode: Boolean,
        isPowerSaveMode: Boolean,
        screenOffDurationMs: Long,
    ): Long {
        if (isDeviceIdleMode) {
            return DEEP_DOZE_INTERVAL_MS
        }
        if (isInteractive && !isPowerSaveMode) {
            return ACTIVE_SESSION_POLL_INTERVAL_MS
        }
        // Power save mode or screen off
        val duration = screenOffDurationMs.coerceAtLeast(0L)
        return when {
            duration < STEP1_THRESHOLD_MS -> SCREEN_OFF_STEP1_INTERVAL_MS
            duration < STEP2_THRESHOLD_MS -> SCREEN_OFF_STEP2_INTERVAL_MS
            else -> DEEP_DOZE_INTERVAL_MS
        }
    }

    fun computeAnnounceInterval(
        isInteractive: Boolean,
        isDeviceIdleMode: Boolean,
    ): Long {
        return if (isInteractive && !isDeviceIdleMode) {
            ACTIVE_ANNOUNCE_INTERVAL_MS
        } else {
            BACKGROUND_ANNOUNCE_INTERVAL_MS
        }
    }
}
