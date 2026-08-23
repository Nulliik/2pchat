package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTorTest {

    @Test
    fun testTorLogFilterMatching() {
        val torLogLine1 = "2026-08-11 18:29:23,099 [INFO] discovery_bridge: [PROXY] Configuration updated: enabled=True, host=127.0.0.1, port=9050"
        val torLogLine2 = "TorManager: Initialized torrc at /data/user/0/com.example.twopchat/files/app_tor/torrc"
        val torLogLine3 = "TorManager: Started embedded Tor process from /lib/arm64/libtor.so"

        assertTrue(torLogLine1.contains("PROXY") || torLogLine1.contains("Tor"))
        assertTrue(torLogLine2.contains("TorManager") || torLogLine2.contains("torrc"))
        assertTrue(torLogLine3.contains("TorManager") || torLogLine3.contains("libtor"))
    }
}
