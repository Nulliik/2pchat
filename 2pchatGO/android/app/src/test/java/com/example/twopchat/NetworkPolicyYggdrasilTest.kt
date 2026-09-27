package com.example.twopchat

import com.example.twopchat.config.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPolicyYggdrasilTest {
    @Test
    fun `yggdrasil mode excludes LAN even when Tor is available`() {
        assertEquals(12, ProxyConfig.transportPolicyFlags(torStrict = false, yggdrasilEnabled = true))
        assertEquals(8, ProxyConfig.transportPolicyFlags(torStrict = true, yggdrasilEnabled = true))
        assertEquals(31, ProxyConfig.transportPolicyFlags(torStrict = false, yggdrasilEnabled = false))
    }
}
