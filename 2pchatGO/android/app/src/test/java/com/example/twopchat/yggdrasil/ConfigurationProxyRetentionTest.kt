package com.example.twopchat.yggdrasil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationProxyRetentionTest {
    @Test
    fun retainsOnlyLivePeersAndCallerCanPreserveMinimumRedundancy() {
        val peers = """[
            {"URI":"tcp://a.example:1","Up":true,"Cost":2,"Latency":20},
            {"URI":"tcp://b.example:2","Up":true,"Cost":1,"Latency":10},
            {"URI":"tcp://c.example:3","Up":false,"Cost":0,"Latency":1}
        ]"""
        val retained = ConfigurationProxy.stableLivePeerUris(peers, emptySet())
        assertEquals(listOf("tcp://b.example:2", "tcp://a.example:1"), retained)
        assertTrue(retained.size < ConfigurationProxy.MIN_RETAINED_PUBLIC_PEERS)
    }
}
