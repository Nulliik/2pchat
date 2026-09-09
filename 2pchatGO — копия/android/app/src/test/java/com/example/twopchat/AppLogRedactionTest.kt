package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLogRedactionTest {

    @Test
    fun testIpAndFingerprintRedaction() {
        val sample = "Connected to peer 192.168.1.50:50001 with fingerprint 0123456789abcdef0123456789abcdef01234567"
        val redacted = AppLog.redactSensitive(sample)
        assertFalse(redacted.contains("192.168.1.50"))
        assertFalse(redacted.contains("0123456789abcdef0123456789abcdef01234567"))
        assertEquals("Connected to peer <ip> with fingerprint <fingerprint>", redacted)
    }

    @Test
    fun testOnionAndBase64KeyRedaction() {
        val onion = "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion"
        val sample = "Connecting to onion $onion with key dGVzdGluZ19zZWNyZXRfa2V5XzMyX2J5dGVzX2xvbmdfMTI="
        val redacted = AppLog.redactSensitive(sample)
        assertFalse(redacted.contains(onion))
        assertFalse(redacted.contains("dGVzdGluZ19zZWNyZXRfa2V5XzMyX2J5dGVzX2xvbmdfMTI="))
        assertEquals("Connecting to onion <onion> with key <key>", redacted)
    }

    @Test
    fun testPrivateDirRedaction() {
        val privateDir = "/data/user/0/com.example.twopchat/files"
        val sample = "Writing identity to /data/user/0/com.example.twopchat/files/identity.key"
        val redacted = AppLog.redactSensitive(sample, privateDir)
        assertFalse(redacted.contains("/data/user/0/com.example.twopchat/files"))
        assertEquals("Writing identity to <app-private-dir>/identity.key", redacted)
    }
}
