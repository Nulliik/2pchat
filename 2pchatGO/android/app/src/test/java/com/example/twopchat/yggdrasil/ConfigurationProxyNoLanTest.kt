package com.example.twopchat.yggdrasil

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigurationProxyNoLanTest {
    @Test
    fun removesExistingListenersAndMulticastRules() {
        val config = JSONObject()
            .put("Listen", JSONArray().put("tcp://0.0.0.0:18227"))
            .put("MulticastInterfaces", JSONArray().put(JSONObject().put("Listen", true).put("Beacon", true)))

        ConfigurationProxy.disableLocalPeering(config)

        assertEquals(0, config.getJSONArray("Listen").length())
        assertEquals(0, config.getJSONArray("MulticastInterfaces").length())
    }

    @Test
    fun enablesLocalPeeringWhenConfigured() {
        val config = JSONObject()
        ConfigurationProxy.setLocalPeeringEnabled(config, true)

        assertEquals(1, config.getJSONArray("Listen").length())
        assertEquals("tcp://0.0.0.0:18227", config.getJSONArray("Listen").getString(0))
        assertEquals(1, config.getJSONArray("MulticastInterfaces").length())
        assertEquals(true, config.getJSONArray("MulticastInterfaces").getJSONObject(0).getBoolean("Beacon"))
        assertEquals(true, config.getJSONArray("MulticastInterfaces").getJSONObject(0).getBoolean("Listen"))
    }
}
