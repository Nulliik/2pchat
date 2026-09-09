package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertEquals
import org.junit.Test

class AppMotionDurationScaleTest {
    @Test
    fun enabledAnimationsUseNormalDurationScale() {
        val scale = AppMotionDurationScale(
            animationsEnabled = true,
            systemScaleFactor = 0.5f,
        )

        assertEquals(0.5f, scale.scaleFactor)
    }

    @Test
    fun disabledAnimationsUseZeroDurationScale() {
        val scale = AppMotionDurationScale(animationsEnabled = false)

        assertEquals(0f, scale.scaleFactor)
    }

    @Test
    fun durationScaleUpdatesWithoutRecreatingTheWindow() {
        val scale = AppMotionDurationScale(animationsEnabled = true)

        scale.animationsEnabled = false

        assertEquals(0f, scale.scaleFactor)
    }

    @Test
    fun negativeSystemScaleIsClamped() {
        val scale = AppMotionDurationScale(systemScaleFactor = -1f)

        assertEquals(0f, scale.scaleFactor)
    }
}
