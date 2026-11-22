package com.example.twopchat.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingPersistenceOrderingTest {
    @Test
    fun persistenceCompletesBeforeDispatchAndReceipt() {
        val events = mutableListOf<String>()

        val saved = persistThenDispatch(
            persist = { events += "persist" },
            dispatch = { events += "dispatch" },
            receipt = { events += "receipt" },
        )

        assertTrue(saved)
        assertEquals(listOf("persist", "dispatch", "receipt"), events)
    }

    @Test
    fun persistenceFailureDoesNotDispatchOrAcknowledge() {
        val events = mutableListOf<String>()

        val saved = persistThenDispatch(
            persist = { throw IllegalStateException("disk failure") },
            dispatch = { events += "dispatch" },
            receipt = { events += "receipt" },
            onPersistFailure = { events += "failed" },
        )

        assertFalse(saved)
        assertEquals(listOf("failed"), events)
    }
}