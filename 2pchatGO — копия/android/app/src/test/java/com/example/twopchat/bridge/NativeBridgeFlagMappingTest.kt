package com.example.twopchat.bridge

import com.example.twopchat.NativeBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeBridgeFlagMappingTest {

    @Test
    fun testNativeBridgeFlagsMatchSharedPolicyFlagsJson() {
        val stream = javaClass.classLoader!!.getResourceAsStream("policy_flags.json")
        checkNotNull(stream) { "policy_flags.json not found in test resources" }
        val jsonText = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(jsonText)

        assertEquals(json.getInt("POLICY_FLAG_ALLOW_LAN"), NativeBridge.POLICY_FLAG_ALLOW_LAN)
        assertEquals(json.getInt("POLICY_FLAG_ALLOW_WAN"), NativeBridge.POLICY_FLAG_ALLOW_WAN)
        assertEquals(json.getInt("POLICY_FLAG_ALLOW_YGGDRASIL"), NativeBridge.POLICY_FLAG_ALLOW_YGGDRASIL)
        assertEquals(json.getInt("POLICY_FLAG_ALLOW_ONION"), NativeBridge.POLICY_FLAG_ALLOW_ONION)
        assertEquals(json.getInt("POLICY_FLAG_ALLOW_LOCAL_DNS"), NativeBridge.POLICY_FLAG_ALLOW_LOCAL_DNS)
    }
}
