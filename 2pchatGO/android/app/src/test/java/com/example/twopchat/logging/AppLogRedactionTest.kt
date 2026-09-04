package com.example.twopchat.logging

import com.example.twopchat.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRedactionTest {
    @Test fun redactsIpv4WithPort() {
        val out = AppLog.redactSensitive("peer @ 192.168.1.10:4455")
        assertFalse(out.contains("192.168.1.10"))
        assertFalse(out.contains("4455"))
    }

    @Test fun redactsIpv6Bracketed() {
        assertFalse(AppLog.redactSensitive("x [2001:db8::1]:9000 y").contains("2001:db8"))
    }

    @Test fun redactsOnion() {
        val onion = "a".repeat(56) + ".onion"
        assertFalse(AppLog.redactSensitive("svc $onion up").contains(onion))
    }

    @Test fun redactsHexFingerprint() {
        val fp = "ab".repeat(32)
        val out = AppLog.redactSensitive("fp=$fp")
        assertFalse(out.contains(fp))
        assertEquals("fp=<fingerprint>", out)
        val shortFp = SafeLog.fp(fp)
        assertTrue(shortFp.startsWith("abababab"))
        assertEquals(shortFp, AppLog.redactSensitive(shortFp))
    }

    @Test fun redactsBase64Key() {
        val b64 = "QUJD".repeat(12) + "=="
        assertFalse(AppLog.redactSensitive("key=$b64").contains(b64))
    }

    @Test fun keepsPlainText() {
        assertEquals("hello 12 world", AppLog.redactSensitive("hello 12 world"))
    }
}
