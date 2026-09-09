package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.service.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIntentSecurityTest {

    @Test
    fun testIntentExplicitPackageEnforcement() {
        val targetPackage = "com.example.twopchat"
        val expectedPackage = "com.example.twopchat"

        assertTrue(NotificationActionReceiver.isPackageMatch(targetPackage, expectedPackage))
        assertFalse(NotificationActionReceiver.isPackageMatch("com.attacker.spoofapp", expectedPackage))
    }

    @Test
    fun testUnboundPackageIntentRejected() {
        val nullPackage: String? = null
        val emptyPackage = ""

        assertFalse(NotificationActionReceiver.isPackageMatch(nullPackage, "com.example.twopchat"))
        assertFalse(NotificationActionReceiver.isPackageMatch(emptyPackage, "com.example.twopchat"))
    }
}
