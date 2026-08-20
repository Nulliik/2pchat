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
            statements.any { it.contains("cache_size = -64000", ignoreCase = true) })

        assertTrue("Must configure busy_timeout to prevent SQLiteBusyException lockups",
            statements.any { it.contains("busy_timeout = 10000", ignoreCase = true) })

        assertTrue("Must configure mmap_size for fast memory-mapped page access",
            statements.any { it.contains("mmap_size", ignoreCase = true) })

        assertEquals(6, statements.size)
    }

    @Test
    fun testSqlitePlaintextHeaderMagicBytes() {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertEquals(16, magic.size)
        assertEquals(0x53.toByte(), magic[0]) // 'S'
        assertEquals(0x51.toByte(), magic[1]) // 'Q'
        assertEquals(0x4c.toByte(), magic[2]) // 'L'
        assertEquals(0x69.toByte(), magic[3]) // 'i'
        assertEquals(0x74.toByte(), magic[4]) // 't'
        assertEquals(0x65.toByte(), magic[5]) // 'e'
    }
}
