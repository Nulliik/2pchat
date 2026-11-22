package com.example.twopchat

import com.example.twopchat.config.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPolicyYggdrasilTest {
    @Test
    fun `strict mode denies all when Tor is disabled or not running`() {
        assertEquals(0, ProxyConfig.transportPolicyFlags(torStrict = true, torUsable = false, yggdrasilEnabled = false))
        assertEquals(0, ProxyConfig.transportPolicyFlags(torStrict = true, torUsable = false, yggdrasilEnabled = true))
    }

    @Test
    fun `strict mode allows Onion only when Tor is enabled and running`() {
        assertEquals(8, ProxyConfig.transportPolicyFlags(torStrict = true, torUsable = true, yggdrasilEnabled = false))
        assertEquals(8, ProxyConfig.transportPolicyFlags(torStrict = true, torUsable = true, yggdrasilEnabled = true))
    }

    @Test
    fun `yggdrasil mode excludes LAN when strict mode is off`() {
        assertEquals(12, ProxyConfig.transportPolicyFlags(torStrict = false, torUsable = false, yggdrasilEnabled = true))
        assertEquals(31, ProxyConfig.transportPolicyFlags(torStrict = false, torUsable = false, yggdrasilEnabled = false))
    }
}
