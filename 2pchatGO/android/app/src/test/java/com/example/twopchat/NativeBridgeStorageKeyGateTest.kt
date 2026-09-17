package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class NativeBridgeStorageKeyGateTest {

    @Test
    fun loadFailurePropagatesBeforeNativeInstall() {
        val failure = GeneralSecurityException("keystore locked")
        var installed: ByteArray? = null
        var ran = false
        val error = assertThrows(GeneralSecurityException::class.java) {
            NativeBridge.withStorageKey(
                load = { throw failure },
                install = { installed = it.copyOf(); true },
                action = { ran = true },
            )
        }
        assertEquals(failure, error)
        assertFalse(installed != null)
        assertFalse(ran)
    }

    @Test
    fun invalidKeyLengthRejectedBeforeNativeInstall() {
        var installed = false
        val error = assertThrows(IllegalStateException::class.java) {
            NativeBridge.withStorageKey(
                load = { ByteArray(16) },
                install = { installed = true; true },
                action = { },
            )
        }
        assertTrue(error.message!!.contains("length"))
        assertFalse(installed)
    }

    @Test
    fun nativeRejectionFailsClosedAndZeroizesKey() {
        var handed: ByteArray? = null
        var ran = false
        val error = assertThrows(IllegalStateException::class.java) {
            NativeBridge.withStorageKey(
                load = { ByteArray(32) { 3 } },
                install = { handed = it; false },
                action = { ran = true },
            )
        }
        assertTrue(error.message!!.contains("rejected"))
        assertTrue("installed key must be zeroized after rejection", checkNotNull(handed).all { it == 0.toByte() })
        assertFalse(ran)
    }

    @Test
    fun successfulInstallRunsActionThenZeroizesKey() {
        var handed: ByteArray? = null
        var ran = false
        val result = NativeBridge.withStorageKey(
            load = { ByteArray(32) { 5 } },
            install = { handed = it; true },
            action = { ran = true; "ok" },
        )
        assertEquals("ok", result)
        assertTrue(ran)
        assertTrue("installed key must be zeroized after use", checkNotNull(handed).all { it == 0.toByte() })
    }

    @Test
    fun keyIsZeroizedEvenWhenActionThrows() {
        var handed: ByteArray? = null
        assertThrows(IllegalStateException::class.java) {
            NativeBridge.withStorageKey(
                load = { ByteArray(32) { 6 } },
                install = { handed = it; true },
                action = { error("action failed after install") },
            )
        }
        assertTrue("installed key must be zeroized when action throws", checkNotNull(handed).all { it == 0.toByte() })
    }
}
