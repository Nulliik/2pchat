package com.example.twopchat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkTrafficStatsInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkTrafficStats.reset(context)
    }

    @After
    fun tearDown() {
        NetworkTrafficStats.reset(context)
    }

    @Test
    fun persistsAndAggregatesTrafficAcrossProtocolsAndCategories() {
        NetworkTrafficStats.record(
            context,
            TrafficProtocol.DIRECT_P2P,
            TrafficCategory.MESSAGES,
            TrafficDirection.SENT,
            bytes = 120L,
            items = 2L,
        )
        NetworkTrafficStats.record(
            context,
            TrafficProtocol.YGGDRASIL,
            TrafficCategory.STICKERS,
            TrafficDirection.RECEIVED,
            bytes = 480L,
        )

        val snapshot = NetworkTrafficStats.snapshot(context)

        assertEquals(120L, snapshot.byProtocol.getValue(TrafficProtocol.DIRECT_P2P).sentBytes)
        assertEquals(480L, snapshot.byProtocol.getValue(TrafficProtocol.YGGDRASIL).receivedBytes)
        assertEquals(120L, snapshot.byCategory.getValue(TrafficCategory.MESSAGES).sentBytes)
        assertEquals(480L, snapshot.byCategory.getValue(TrafficCategory.STICKERS).receivedBytes)
        assertEquals(600L, snapshot.total.totalBytes)
        assertEquals(3L, snapshot.total.totalItems)
    }

    @Test
    fun resetClearsAllCountersAndStartsNewPeriod() {
        NetworkTrafficStats.record(
            context,
            TrafficProtocol.DIRECT_P2P,
            TrafficCategory.FILES,
            TrafficDirection.SENT,
            bytes = 1_024L,
        )
        val beforeReset = NetworkTrafficStats.snapshot(context).startedAtMs

        NetworkTrafficStats.reset(context)
        val afterReset = NetworkTrafficStats.snapshot(context)

        assertEquals(0L, afterReset.total.totalBytes)
        assertEquals(0L, afterReset.total.totalItems)
        assertTrue(afterReset.startedAtMs >= beforeReset)
    }
}
