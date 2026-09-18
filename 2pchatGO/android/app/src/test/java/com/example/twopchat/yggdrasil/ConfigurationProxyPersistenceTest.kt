package com.example.twopchat.yggdrasil

import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ConfigurationProxyPersistenceTest {

    private val directory: File = Files.createTempDirectory("ygg-conf-test").toFile()
    private val file = File(directory, "yggdrasil.conf")

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
        directory.deleteRecursively()
    }

    @Test
    fun readFailsClosedOnEnvelopeDecryptionFailureWithoutRegeneration() {
        file.writeText("enc:v1:corrupted-envelope")
        val error = assertThrows(IllegalStateException::class.java) {
            ConfigurationProxy.readStoredConfig(file) { null }
        }
        assertTrue(error.message!!.contains("decryption"))
        assertEquals("enc:v1:corrupted-envelope", file.readText())
        assertFalse(File(directory, "yggdrasil.conf.tmp").exists())
    }

    @Test
    fun readFailsClosedOnUnrecognizedFormatInsteadOfFreshIdentity() {
        file.writeText("garbage-not-json")
        assertThrows(IllegalStateException::class.java) {
            ConfigurationProxy.readStoredConfig(file) { null }
        }
        assertEquals("garbage-not-json", file.readText())
    }

    @Test
    fun readFailsClosedWhenDecryptedIdentityMissing() {
        file.writeText("enc:v1:envelope")
        assertThrows(IllegalStateException::class.java) {
            ConfigurationProxy.readStoredConfig(file) { """{"AdminListen":"none"}""" }
        }
        assertEquals("enc:v1:envelope", file.readText())
    }

    @Test
    fun legacyPlainJsonConfigIsReadWithoutRegeneration() {
        val legacy = """{"PrivateKey":"legacy-key","AdminListen":"none"}"""
        file.writeText(legacy)
        val config = ConfigurationProxy.readStoredConfig(file) { error("decrypt must not run for plaintext") }
        assertEquals("legacy-key", org.json.JSONObject(config).getString("PrivateKey"))
        assertEquals(legacy, file.readText())
    }

    @Test
    fun persistFailureKeepsPreviousConfigurationOnDisk() {
        val previous = "enc:v1:previous-configuration"
        file.writeText(previous)
        val exception = assertThrows(IllegalStateException::class.java) {
            ConfigurationProxy.persistConfig(
                file,
                """{"PrivateKey":"new"}""",
                encrypt = { "not-an-envelope" },
            )
        }
        assertTrue(exception.message!!.contains("encryption failed"))
        assertEquals(previous, file.readText())
    }

    @Test
    fun replaceFailurePropagatesAndPreservesPreviousConfiguration() {
        val previous = "enc:v1:previous-configuration"
        file.writeText(previous)
        val exception = assertThrows(IllegalStateException::class.java) {
            ConfigurationProxy.persistConfig(
                file,
                """{"PrivateKey":"next"}""",
                encrypt = { "enc:v1:new-configuration" },
                replace = { _, _ -> false },
            )
        }
        assertTrue(exception.message!!.contains("original preserved"))
        assertEquals(previous, file.readText())
        File(directory, "yggdrasil.conf.tmp").delete()
    }

    @Test
    fun successfulPersistReplacesAtomicallyWithEncryptedEnvelope() {
        file.writeText("enc:v1:old")
        ConfigurationProxy.persistConfig(
            file,
            """{"PrivateKey":"next"}""",
            encrypt = { "enc:v1:new" },
            replace = { source, target ->
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            },
        )
        assertEquals("enc:v1:new", file.readText())
        assertFalse(File(directory, "yggdrasil.conf.tmp").exists())
    }
}
