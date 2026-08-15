package com.example.twopchat

import com.example.twopchat.data.DatabaseTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseTuningTest {

    @Test
    fun testPragmaStatementsContainCriticalPerformanceTuning() {
        val statements = DatabaseTuning.PRAGMA_STATEMENTS

        assertTrue("Must configure synchronous = NORMAL for WAL performance",
            statements.any { it.contains("synchronous = NORMAL", ignoreCase = true) })

        assertTrue("Must configure foreign_keys = ON for integrity",
            statements.any { it.contains("foreign_keys = ON", ignoreCase = true) })

        assertTrue("Must configure temp_store = MEMORY to avoid unencrypted disk spills",
            statements.any { it.contains("temp_store = MEMORY", ignoreCase = true) })

        assertTrue("Must configure cache_size to keep decrypted pages in RAM",
            statements.any { it.contains("cache_size = -2000", ignoreCase = true) })

        assertTrue("Must configure busy_timeout to prevent SQLiteBusyException lockups",
            statements.any { it.contains("busy_timeout = 5000", ignoreCase = true) })

        assertEquals(5, statements.size)
    }
}
