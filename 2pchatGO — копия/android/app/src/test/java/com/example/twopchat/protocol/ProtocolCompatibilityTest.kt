package com.example.twopchat.protocol

import com.example.twopchat.group.protocol.GroupWireProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolCompatibilityTest {
    @Test fun absentSessionDoesNotEnableFeatures() {
        assertNull(NegotiatedSession.fromNative(null))
        assertNull(NegotiatedSession.fromNative("null"))
    }

    @Test fun nativeSnapshotKeepsLegacySeparateFromProtocolVersion() {
        val snapshot = NegotiatedSession.fromNative("""{
          "protocol_version":0,"active_capabilities":["pairwise_x3dh_v1"],
          "peer_is_outdated":false,"peer_is_legacy":true
        }""")!!
        assertTrue(snapshot.peerIsLegacy)
        assertFalse(snapshot.supports(Capability.GROUP_SUCCESSION_V1))
    }

    @Test fun unknownCapabilitiesDoNotEnableKnownFeatures() {
        val snapshot = NegotiatedSession.fromNative("""{
          "protocol_version":1,"active_capabilities":["future_group_v9","group_suite_v1"],
          "peer_is_outdated":false,"peer_is_legacy":false
        }""")!!
        assertTrue(snapshot.supports(Capability.GROUP_SUITE_V1))
        assertFalse(snapshot.supports(Capability.GROUP_SUITE_V2))
    }

    @Test fun syncBatchRequiresCapabilitiesOfItsContainedEvents() {
        val batch = JSONObject("""{
          "type":"group_sync_batch_v1","events":[{
            "type":"group_event_v1","is_tombstoned":true,
            "crypto_suite":"2pchat-epoch-aes256gcm-ed25519-v2"
          }]
        }""")
        assertEquals(setOf(Capability.GROUP_SUITE_V1, Capability.GROUP_SUITE_V2,
            Capability.GROUP_TOMBSTONES_V1), GroupWireProtocol.requiredCapabilities(batch))
    }

    @Test fun keyPackageAndSuccessionHaveIndependentRequirements() {
        val key = JSONObject("""{"type":"group_key_package_v1","suite":"2pchat-epoch-aes256gcm-ed25519-v2"}""")
        assertTrue(GroupWireProtocol.requiredCapabilities(key).contains(Capability.GROUP_SUITE_V2))
        val claim = JSONObject("""{"type":"group_succession_claim_v1"}""")
        assertTrue(GroupWireProtocol.requiredCapabilities(claim).contains(Capability.GROUP_SUCCESSION_V1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nestedSyncBatchesAreRejected() {
        GroupWireProtocol.requiredCapabilities(JSONObject("""{
          "type":"group_sync_batch_v1","events":[{"type":"group_sync_batch_v1","events":[]}]
        }"""))
    }
}
