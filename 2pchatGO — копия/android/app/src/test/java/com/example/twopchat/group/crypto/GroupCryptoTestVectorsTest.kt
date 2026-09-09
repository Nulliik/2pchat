package com.example.twopchat.group.crypto

import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupWireEvent
import com.example.twopchat.group.protocol.GroupWireProtocol
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroupCryptoTestVectorsTest {

    @Test
    fun katVectorFilesAreByteIdentical() {
        val resStream = javaClass.classLoader!!.getResourceAsStream("group_crypto_test_vectors.json")
        assertNotNull("Resource group_crypto_test_vectors.json must exist", resStream)
        val resBytes = resStream!!.use { it.readBytes() }

        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidatePaths = listOf(
            File(currentDir, "core-go/testdata/group_crypto_test_vectors.json"),
            File(currentDir, "../core-go/testdata/group_crypto_test_vectors.json"),
            File(currentDir, "android/core-go/testdata/group_crypto_test_vectors.json"),
        )
        val goFile = candidatePaths.firstOrNull { it.exists() }
        assertNotNull("core-go/testdata/group_crypto_test_vectors.json must exist", goFile)
        val goBytes = goFile!!.readBytes()

        assertArrayEquals("Vectors file in resources and core-go/testdata must be byte-identical", resBytes, goBytes)
    }

    @Test
    fun independentlyGeneratedVectorsMatch() {
        val stream = javaClass.classLoader!!.getResourceAsStream("group_crypto_test_vectors.json")!!
        val jsonText = stream.bufferedReader().use { it.readText() }
        val root = JSONObject(jsonText)

        // 1. Roster hash vectors
        val rosterVectors = root.getJSONArray("roster_hash_vectors")
        for (i in 0 until rosterVectors.length()) {
            val vec = rosterVectors.getJSONObject(i)
            val name = vec.getString("name")
            val expectedHash = vec.getString("expected_roster_hash")
            val entriesArr = vec.getJSONArray("entries")
            val entries = mutableListOf<Pair<String, String>>()
            for (j in 0 until entriesArr.length()) {
                val str = entriesArr.getString(j)
                val parts = str.split(":", limit = 2)
                entries.add(parts[0] to parts[1])
            }

            val actualHash = GroupWireProtocol.computeRosterHashFromEntries(entries)
            assertEquals("Vector $name roster hash must match", expectedHash, actualHash)
        }

        // 2. Event ID vectors
        val eventVectors = root.getJSONArray("event_id_vectors")
        for (i in 0 until eventVectors.length()) {
            val vec = eventVectors.getJSONObject(i)
            val name = vec.getString("name")
            val expectedEventId = vec.getString("expected_event_id")
            val expectedCanonical = vec.getString("canonical_for_signature")
            val fields = vec.getJSONObject("event_fields")

            val event = GroupWireEvent(
                groupId = fields.getString("group_id"),
                eventId = "",
                epoch = fields.getLong("epoch"),
                kind = GroupEventKind.fromWire(fields.getString("kind")),
                authorFingerprint = fields.getString("author_fingerprint"),
                authorDeviceId = fields.getString("author_device_id"),
                authorSigningKey = fields.getString("author_signing_key"),
                authorSequence = fields.getLong("author_sequence"),
                previousAuthorEvent = fields.optString("previous_author_event").takeIf { it.isNotBlank() },
                controlHead = fields.optString("control_head").takeIf { it.isNotBlank() },
                hlcPhysicalMs = fields.getLong("hlc_physical_ms"),
                hlcLogical = fields.getInt("hlc_logical"),
                targetEventId = fields.optString("target_event_id").takeIf { it.isNotBlank() },
                nonceBase64 = fields.getString("nonce_base64"),
                ciphertextBase64 = fields.getString("ciphertext_base64"),
                signatureBase64 = "",
                cryptoSuite = fields.getString("crypto_suite"),
                expiresAtMs = fields.optLong("expires_at_ms", 0L),
            )

            val actualCanonical = event.canonicalForSignature()
            assertEquals("Vector $name canonical signature payload must match", expectedCanonical, actualCanonical)

            val actualEventId = event.computedEventId()
            assertEquals("Vector $name computed eventId must match", expectedEventId, actualEventId)
        }
    }
}
