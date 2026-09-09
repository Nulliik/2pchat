package com.example.twopchat

import com.example.twopchat.relay.*
import org.junit.Assert.*
import org.junit.Test

class EndpointRetentionTest {
    private val now = 1_800_000_000_000L
    private val fp = "a".repeat(64)
    private fun row(ep: String, friend: Boolean = false) = EndpointRecord(fp, ep, EndpointSource.DISCOVERY, now, now, savedContact = friend)

    @Test fun successfulUseExtendsLifeAndRepeatedDialsDoNotInventSuccessfulDays() {
        val discovered = row("8.8.8.8:50001")
        val successful = EndpointRetention.success(discovered, now)
        assertEquals(now + 2 * 60 * 60_000L, EndpointRetention.expiresAt(discovered))
        assertEquals(now + 30 * EndpointRetention.DAY, EndpointRetention.expiresAt(successful))
        var repeated = successful
        repeat(30) { repeated = EndpointRetention.success(repeated, now + it * 1000) }
        assertEquals(1, repeated.successDays)
        repeated = EndpointRetention.success(repeated, now + EndpointRetention.DAY)
        repeated = EndpointRetention.success(repeated, now + 2 * EndpointRetention.DAY)
        assertEquals(3, repeated.successDays)
        assertEquals(repeated.lastSuccess + 90 * EndpointRetention.DAY, EndpointRetention.expiresAt(repeated))
    }

    @Test fun trackerCannotKeepAnOldSuccessfulAddressFresh() {
        val old = EndpointRetention.success(row("8.8.8.8:50001"), now)
        val observedAgain = old.copy(lastSeen = now + 90 * EndpointRetention.DAY)
        assertEquals(EndpointRetention.expiresAt(old), EndpointRetention.expiresAt(observedAgain))
        assertTrue(EndpointRetention.candidates(listOf(observedAgain), observedAgain.lastSeen, false).isEmpty())
    }

    @Test fun stableTransportsLiveLongerThanPublicAndLanRoutes() {
        val endpoints = listOf("192.168.1.2:50001", "8.8.8.8:50001", "[200:1234::1]:50001", "${"b".repeat(56)}.onion:50001")
        val days = listOf(7, 30, 180, 180)
        endpoints.zip(days).forEach { (ep, duration) ->
            assertEquals(now + duration * EndpointRetention.DAY, EndpointRetention.expiresAt(EndpointRetention.success(row(ep), now)))
        }
        assertEquals(EndpointKind.YGGDRASIL, EndpointRetention.kind("[2ff::1]:50001"))
        assertEquals(EndpointKind.YGGDRASIL, EndpointRetention.kind("[3ff::1]:50001"))
        assertEquals(EndpointKind.PUBLIC, EndpointRetention.kind("[400::1]:50001"))
    }

    @Test fun absentFriendsKeepLastWorkingStableRoutesAsReserves() {
        val tor = EndpointRetention.success(row("${"b".repeat(56)}.onion:50001", true), now)
        val ygg = EndpointRetention.success(row("[200::1]:50001", true), now)
        val future = now + 1000 * EndpointRetention.DAY
        val retained = EndpointRetention.retain(listOf(tor, ygg), future)
        assertEquals(2, retained.size)
        assertTrue(EndpointRetention.candidates(retained, future, false).isEmpty())
        assertEquals(2, EndpointRetention.candidates(retained, future, true).size)
        assertTrue(EndpointRetention.candidates(listOf(EndpointRetention.success(tor, future)), future, false).isNotEmpty())
    }

    @Test fun directOnlyFriendKeepsLastRouteAndMigrationDoesNotInventSuccess() {
        val legacy = row("8.8.8.8:50001", true).copy(source = EndpointSource.MIGRATED)
        val future = now + 1000 * EndpointRetention.DAY
        val retained = EndpointRetention.retain(listOf(legacy), future)
        assertEquals(1, retained.size)
        assertEquals(0L, retained.single().lastSuccess)
        assertEquals(0, retained.single().successDays)
        assertTrue(EndpointRetention.candidates(retained, future, false).isEmpty())
        assertEquals(listOf(legacy.endpoint), EndpointRetention.candidates(retained, future, true))
    }

    @Test fun discoveryCannotReplaceLastWorkingOnionReserve() {
        val good = EndpointRetention.success(row("${"b".repeat(56)}.onion:50001", true), now)
        val unknown = row("${"c".repeat(56)}.onion:50001", true).copy(lastSeen = now + EndpointRetention.DAY)
        assertEquals(setOf(good.endpoint), EndpointRetention.protected(listOf(good, unknown)))
    }

    @Test fun failedAttemptPreservesHistoryButBacksOffUntilUserRequestsConnection() {
        val good = EndpointRetention.success(row("8.8.8.8:50001", true), now)
        val failed = EndpointRetention.failure(good, now + 1)
        assertEquals(good.lastSuccess, failed.lastSuccess)
        assertEquals(good.successDays, failed.successDays)
        assertTrue(EndpointRetention.candidates(listOf(failed), now + 100, false).isEmpty())
        assertEquals(listOf(good.endpoint), EndpointRetention.candidates(listOf(failed), now + 100, true))
        val recovered = EndpointRetention.success(failed, now + 100)
        assertEquals(0, recovered.failures)
        assertEquals(0L, recovered.retryAfter)
    }

    @Test fun activeRoutesSurviveCleanupEvenWithoutSavedContact() {
        val route = row("8.8.8.8:50001")
        assertEquals(listOf(route), EndpointRetention.retain(listOf(route), now + EndpointRetention.DAY, setOf(route.endpoint)))
        assertTrue(EndpointRetention.retain(listOf(route), now + EndpointRetention.DAY).isEmpty())
    }

    @Test fun perPeerLimitEvictsCandidatesInsteadOfRejectingNewSuccessfulAddress() {
        val candidates = (1..32).map { row("8.8.4.$it:50001") }
        val working = EndpointRetention.success(row("9.9.9.9:50001", true), now + 1)
        val retained = EndpointRetention.retain(candidates + working, now + 2)
        assertEquals(16, retained.size)
        assertTrue(retained.contains(working))
    }

    @Test fun globalBudgetDoesNotEraseFriendReserves() {
        val friends = (0..8200).map { index ->
            EndpointRetention.success(row("8.8.8.8:50001", true).copy(fingerprint = index.toString()), now)
        }
        assertEquals(friends.size, EndpointRetention.trimCache(friends, now + 1000 * EndpointRetention.DAY).size)
        val candidates = friends.map { it.copy(savedContact = false, lastSuccess = 0, successDays = 0) }
        assertEquals(EndpointRetention.MAX_CACHE_RECORDS, EndpointRetention.trimCache(candidates, now).size)
    }

    @Test fun signedExpiryLimitsUnprovenCandidatesButNotIndependentSuccessEvidence() {
        val signed = row("8.8.8.8:50001").copy(advertisedExpires = now + 1000)
        assertEquals(now + 1000, EndpointRetention.expiresAt(signed))
        assertTrue(EndpointRetention.retain(listOf(signed), now + 1001).isEmpty())
        assertTrue(EndpointRetention.expiresAt(EndpointRetention.success(signed, now)) > signed.advertisedExpires)
    }

    @Test fun canonicalAddressesAndBoundedValidation() {
        assertEquals(EndpointRetention.normalize("[200::1]:50001"), EndpointRetention.normalize("[0200:0:0:0:0:0:0:1]:50001"))
        assertNull(EndpointRetention.normalize("127.0.0.1:42342"))
        assertNull(EndpointRetention.normalize("[::1]:50001"))
        assertFalse(isValidPeerEndpointList((1..17).joinToString(",") { "8.8.8.$it:50001" }))
    }
}
