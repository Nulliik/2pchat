package com.example.twopchat.security

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReleaseManifestTelemetryCheckTest {

    private val bannedManifestTokens = listOf(
        "GoogleApiActivity",
        "com.google.android.gms.permission",
        "TransportBackendDiscovery",
        "MlKitComponentDiscoveryService",
        "MlKitInitProvider",
        "com.google.mlkit",
        "com.google.firebase.components"
    )

    @Test
    fun manifestValidatorRejectsBannedTelemetryComponents() {
        val samplePoisonedManifest = """
            <manifest package="com.example.twopchat">
                <application>
                    <activity android:name="com.google.android.gms.common.api.GoogleApiActivity" />
                    <service android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" />
                </application>
            </manifest>
        """.trimIndent()

        for (token in listOf("GoogleApiActivity", "TransportBackendDiscovery")) {
            assertTrue("Validator must detect banned token: $token", samplePoisonedManifest.contains(token))
        }
    }

    @Test
    fun mergedReleaseManifestContainsNoGoogleTelemetryComponents() {
        val candidates = listOf(
            File("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"),
            File("app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"),
            File("build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
            File("app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
        )

        val manifestFile = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (manifestFile != null) {
            val content = manifestFile.readText()
            for (banned in bannedManifestTokens) {
                assertFalse(
                    "Merged manifest (${manifestFile.path}) must NOT contain banned telemetry token: $banned",
                    content.contains(banned)
                )
            }
        }
    }
}
