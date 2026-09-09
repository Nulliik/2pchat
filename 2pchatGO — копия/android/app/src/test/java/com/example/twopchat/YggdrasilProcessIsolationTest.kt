package com.example.twopchat

import com.example.twopchat.yggdrasil.isYggdrasilServiceProcess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YggdrasilProcessIsolationTest {
    @Test
    fun recognizesDedicatedYggdrasilProcess() {
        assertTrue(isYggdrasilServiceProcess("com.example.twopchat.go:yggdrasil"))
    }

    @Test
    fun doesNotSuppressInitializationInMainOrUnrelatedProcesses() {
        assertFalse(isYggdrasilServiceProcess("com.example.twopchat.go"))
        assertFalse(isYggdrasilServiceProcess("com.example.twopchat.go:worker"))
        assertFalse(isYggdrasilServiceProcess(null))
    }
}
