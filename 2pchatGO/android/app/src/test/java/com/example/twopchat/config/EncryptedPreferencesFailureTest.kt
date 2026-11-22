package com.example.twopchat.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EncryptedPreferencesFailureTest {
    @Test
    fun encryptedInitializationFailureDoesNotDeleteOrFallBackToPlaintextPreferences() {
        var plaintextFallbackCalls = 0
        val filesDir = Files.createTempDirectory("2pchat-prefs-test").toFile()
        val encryptedPrefsFile = File(filesDir.parentFile, "shared_prefs/2pchat_secure_prefs.xml")
        encryptedPrefsFile.parentFile?.mkdirs()
        encryptedPrefsFile.writeText("preserve-encrypted-data")
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                plaintextFallbackCalls++
                error("plaintext SharedPreferences must never be opened")
            }
        }
        var attempts = 0
        P2PPreferences.encryptedPreferencesProvider = {
            attempts++
            throw IllegalStateException("simulated Keystore failure")
        }
        try {
            val error = runCatching { P2PPreferences.prefs(context) }.exceptionOrNull()
            assertNotNull("Encrypted preference initialization must fail closed", error)
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("refusing plaintext fallback"))
            assertEquals("Only one encrypted provider attempt should occur", 1, attempts)
            assertEquals("No regular preferences fallback is permitted", 0, plaintextFallbackCalls)
            assertNull("Failed preferences must not be cached", P2PPreferences.testingPrefsProvider?.invoke())
            assertTrue("Existing encrypted preferences must be preserved", encryptedPrefsFile.exists())
            assertEquals("Existing encrypted preference contents must be preserved", "preserve-encrypted-data", encryptedPrefsFile.readText())
        } finally {
            P2PPreferences.setCachedPrefsForTesting(null)
        }
    }
}
