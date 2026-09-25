package com.example.twopchat.yggdrasil

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Yggdrasil user-space TCP shim liveness contract:
 *
 * 1. Retransmit exhaustion must NOT close the stream. A segment unacked past
 *    MAX_SEGMENT_RETRIES (~65s of RTOs) means a mesh route gap; the peer
 *    side retransmits symmetrically and cumulative ACKs recover the stream
 *    when the route returns. Closing on exhaustion used to tear down the
 *    authenticated session after every transient flap.
 *
 * 2. The local node address must fall back to the last known-good value
 *    while the live lookup is transiently empty. An all-zero source address
 *    makes the peer's shim key every packet under "::" and drop data +
 *    ACKs, which killed the session after one retransmit budget.
 */
class YggdrasilUserSpaceStackTest {

    @Test
    fun retransmitDecisionNeverClosesOpenStream() {
        // Far past the exhaustion threshold: the old code closed the stream
        // here (isClosed=true, clientSocket.close(), session evicted).
        assertEquals(
            RetransmitAction.RETRY,
            retransmitDecision(isClosed = false),
        )
        // The action set must not contain a close/teardown outcome at all.
        assertEquals(
            setOf(RetransmitAction.SKIP, RetransmitAction.RETRY),
            RetransmitAction.values().toSet(),
        )
    }

    @Test
    fun retransmitDecisionSkipsClosedStream() {
        assertEquals(
            RetransmitAction.SKIP,
            retransmitDecision(isClosed = true),
        )
    }

    @Test
    fun maxSegmentRetriesBoundsInitialBackoffPhase() {
        // The exhaustion warning fires at this retry count; the RTO is
        // capped at 8s from 2^5 backoff steps, so total initial phase is
        // ~65s. The constant must stay a positive bound.
        assertTrue(YggdrasilUserSpaceStack.MAX_SEGMENT_RETRIES > 0)
    }

    @Test
    fun localAddressParsesYggdrasilNodeAddress() {
        val resolved = YggdrasilUserSpaceStack.resolveLocalAddress("200:abcd:1234:5678:9abc:def0:1234:5678", null)
        assertEquals(16, resolved.size)
        assertTrue(resolved.any { it != 0.toByte() })
    }

    @Test
    fun localAddressFallsBackToCachedValueWhenLookupEmpty() {
        val cached = ByteArray(16) { (0x20 + it).toByte() }
        val resolved = YggdrasilUserSpaceStack.resolveLocalAddress("", cached)
        assertArrayEquals(cached, resolved)
        val resolvedNull = YggdrasilUserSpaceStack.resolveLocalAddress(null, cached)
        assertArrayEquals(cached, resolvedNull)
    }

    @Test
    fun localAddressFallsBackToCachedValueWhenLookupInvalid() {
        val cached = ByteArray(16) { (0x20 + it).toByte() }
        val resolved = YggdrasilUserSpaceStack.resolveLocalAddress("not-an-ipv6-address", cached)
        assertArrayEquals(cached, resolved)
    }

    @Test
    fun localAddressZeroOnlyWhenNothingKnown() {
        val resolved = YggdrasilUserSpaceStack.resolveLocalAddress(null, null)
        assertEquals(16, resolved.size)
        assertFalse(resolved.any { it != 0.toByte() })
    }
}
