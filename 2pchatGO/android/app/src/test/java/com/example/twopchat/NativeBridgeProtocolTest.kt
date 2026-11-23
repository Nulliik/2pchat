package com.example.twopchat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeBridgeProtocolTest {
    @After
    fun resetOverrides() {
        NativeBridge.resetTestingOverrides()
    }

    @Test
    fun protocolLookupReturnsNullWhenNativeAccessorFails() {
        assertEquals(
            null,
            NativeBridge.readPeerProtocol("peer-fingerprint") {
                throw UnsatisfiedLinkError("missing JNI symbol")
            },
        )
    }

    @Test
    fun protocolLookupUsesNativeSnapshotWhenAvailable() {
        assertEquals(
            "snapshot:peer-fingerprint",
            NativeBridge.readPeerProtocol("peer-fingerprint") { fingerprint -> "snapshot:$fingerprint" },
        )
    }
}
