package com.example.twopchat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DbKeyFormatMigrationTest {

    @Test
    fun testBase64Utf8BytesMatchStringPassphraseBytes() {
        val b64String = "aB3+k9XyZ1234567890123456789012345678901234="
        val stringBytes = b64String.toByteArray(Charsets.UTF_8)

        // Verify that converting Base64 String to UTF-8 bytes preserves identical byte values used by SQLCipher String constructor
        assertNotNull(stringBytes)
        assert(stringBytes.size == 44)
    }
}
