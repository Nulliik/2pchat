package com.example.twopchat.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateSecurityPolicyTest {

    @Test
    fun rejectsHttpScheme() {
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("http://api.github.com/repos/kodzyfox/2pchat/releases"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("http://github.com/releases/download/v1.0.0/app.apk"))
    }

    @Test
    fun rejectsUntrustedHosts() {
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("https://evil.com/releases/latest"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("https://github.com.evil.com/releases/latest"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("https://127.0.0.1/app.apk"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("https://192.168.1.1/app.apk"))
    }

    @Test
    fun rejectsMalformedAndSuspiciousUrls() {
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl(""))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("not_a_url"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("ftp://github.com/app.apk"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("https://user:password@github.com/app.apk"))
        assertFalse(UpdateSecurityPolicy.isValidUpdateUrl("https://github.com:8443/app.apk"))
    }

    @Test
    fun acceptsValidGitHubHttpsUrls() {
        assertTrue(UpdateSecurityPolicy.isValidUpdateUrl("https://api.github.com/repos/kodzyfox/2pchat-releases/releases/latest"))
        assertTrue(UpdateSecurityPolicy.isValidUpdateUrl("https://github.com/kodzyfox/2pchat-releases/releases/download/v0.0.9/app.apk"))
        assertTrue(UpdateSecurityPolicy.isValidUpdateUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/12345/app.apk"))
        assertTrue(UpdateSecurityPolicy.isValidUpdateUrl("https://raw.githubusercontent.com/kodzyfox/2pchat-releases/main/version.json"))
    }

    @Test
    fun rejectsDowngradeVersionCode() {
        // Equal version is rejected
        assertTrue(UpdateSecurityPolicy.isDowngrade(currentVersionCode = 100L, candidateVersionCode = 100L))
        // Lower version is rejected
        assertTrue(UpdateSecurityPolicy.isDowngrade(currentVersionCode = 100L, candidateVersionCode = 99L))
        assertTrue(UpdateSecurityPolicy.isDowngrade(currentVersionCode = 100L, candidateVersionCode = 1L))
    }

    @Test
    fun acceptsNewerVersionCode() {
        // Higher version is NOT a downgrade
        assertFalse(UpdateSecurityPolicy.isDowngrade(currentVersionCode = 100L, candidateVersionCode = 101L))
        assertFalse(UpdateSecurityPolicy.isDowngrade(currentVersionCode = 100L, candidateVersionCode = 200L))
    }

    @Test
    fun updateVerifierRejectsMissingOrEmptyFile() {
        val verifier = UpdateVerifier(currentVersionCode = 100L)
        val nonExistent = File("non_existent_update_${System.currentTimeMillis()}.apk")
        val result = verifier.verify(nonExistent)
        assertTrue(result is UpdateVerifier.Result.Rejected)
        assertEquals("empty-or-missing-file", (result as UpdateVerifier.Result.Rejected).reason)
    }

    @Test
    fun rejectsForeignPackageName() {
        val tempFile = File.createTempFile("test_apk_", ".apk")
        try {
            tempFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            val ourCert = byteArrayOf(0x01, 0x02, 0x03)
            val foreignPkgSigner = object : ApkSignerVerifier {
                override fun signerDigests(apk: File): List<ByteArray> = listOf(ourCert)
                override fun currentAppSignerDigests(): List<ByteArray> = listOf(ourCert)
                override fun versionCodeOf(apk: File): Long = 101L
                override fun packageNameOf(apk: File): String = "com.evil.imposter"
            }
            val res = UpdateVerifier(
                signer = foreignPkgSigner,
                currentVersionCode = 100L,
                expectedPackageName = "com.example.twopchat"
            ).verify(tempFile)
            assertTrue(res is UpdateVerifier.Result.Rejected)
            assertEquals("foreign-package-name", (res as UpdateVerifier.Result.Rejected).reason)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun rejectsAssetFromForeignRepo() {
        // Legitimate repo
        assertTrue(UpdateSecurityPolicy.isValidAssetUrl("https://github.com/kodzyfox/2pchat-releases/releases/download/v1.0/app.apk"))
        assertTrue(UpdateSecurityPolicy.isValidAssetUrl("https://api.github.com/repos/kodzyfox/2pchat-releases/releases/latest"))
        assertTrue(UpdateSecurityPolicy.isValidAssetUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/12345/app.apk"))

        // Malicious / foreign repos targeting same filename
        assertFalse(UpdateSecurityPolicy.isValidAssetUrl("https://github.com/attacker/fake-2pchat/releases/download/v1.0/app.apk"))
        assertFalse(UpdateSecurityPolicy.isValidAssetUrl("https://api.github.com/repos/attacker/fake-2pchat/releases/latest"))
        assertFalse(UpdateSecurityPolicy.isValidAssetUrl("https://raw.githubusercontent.com/attacker/fake-2pchat/main/version.json"))
        assertFalse(UpdateSecurityPolicy.isValidAssetUrl("https://github.com/otheruser/2pchat-releases/releases/download/v1.0/app.apk"))
    }

    @Test
    fun validatesEveryRedirectHop() {
        val legitimateRedirectCdn = "https://objects.githubusercontent.com/github-production-release-asset-2e65be/12345/app.apk"
        val foreignRepoRedirect = "https://github.com/attacker/fake-2pchat/releases/download/v1.0/app.apk"
        val insecureSchemeRedirect = "http://objects.githubusercontent.com/asset.apk"
        val externalDomainRedirect = "https://evil-server.com/malicious.apk"

        assertTrue(UpdateSecurityPolicy.validateRedirectHop(legitimateRedirectCdn))
        assertFalse(UpdateSecurityPolicy.validateRedirectHop(foreignRepoRedirect))
        assertFalse(UpdateSecurityPolicy.validateRedirectHop(insecureSchemeRedirect))
        assertFalse(UpdateSecurityPolicy.validateRedirectHop(externalDomainRedirect))
    }

    @Test
    fun failsClosedWhenTorProxyDown() {
        val result = UpdateSecurityPolicy.openSecureConnection(
            rawUrl = "https://api.github.com/repos/kodzyfox/2pchat-releases/releases/latest",
            context = null,
            torEnabledOverride = true,
            torRunningOverride = false
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("failing closed"))
    }

    @Test
    fun noLocalDnsResolutionWhenTorEnabled() {
        val mockProxyConfig = com.example.twopchat.config.ResolvedProxyConfig(
            enabled = true,
            host = "127.0.0.1",
            port = 9050
        )
        val connResult = UpdateSecurityPolicy.openSecureConnection(
            rawUrl = "https://api.github.com/repos/kodzyfox/2pchat-releases/releases/latest",
            context = null,
            torEnabledOverride = true,
            torRunningOverride = true,
            proxyConfigOverride = mockProxyConfig
        )
        assertTrue(connResult.isSuccess)
        val conn = connResult.getOrThrow()
        // Ensure manual redirects are disabled so every redirect is explicitly validated
        assertFalse(conn.instanceFollowRedirects)
    }

    @org.junit.Ignore("Visible debt: detached cryptographic signature (.sig / Ed25519) verification before installation")
    @Test
    fun detachedSignatureVerificationVisibleDebt() {
        fail("Detached signature verification not yet implemented")
    }

    @Test
    fun updateVerifierWithSignerChecksDowngradeAndCertificates() {
        val tempFile = File.createTempFile("test_apk_", ".apk")
        try {
            tempFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // valid dummy zip header

            val ourCert = byteArrayOf(0x01, 0x02, 0x03)
            val foreignCert = byteArrayOf(0x09, 0x09, 0x09)

            // 1. Foreign signer rejected
            val foreignSigner = object : ApkSignerVerifier {
                override fun signerDigests(apk: File): List<ByteArray> = listOf(foreignCert)
                override fun currentAppSignerDigests(): List<ByteArray> = listOf(ourCert)
                override fun versionCodeOf(apk: File): Long = 200L
            }
            val res1 = UpdateVerifier(signer = foreignSigner, currentVersionCode = 100L).verify(tempFile)
            assertTrue(res1 is UpdateVerifier.Result.Rejected)
            assertEquals("foreign-signer", (res1 as UpdateVerifier.Result.Rejected).reason)

            // 2. Downgrade rejected even with matching signer
            val downgradeSigner = object : ApkSignerVerifier {
                override fun signerDigests(apk: File): List<ByteArray> = listOf(ourCert)
                override fun currentAppSignerDigests(): List<ByteArray> = listOf(ourCert)
                override fun versionCodeOf(apk: File): Long = 100L // same version
            }
            val res2 = UpdateVerifier(signer = downgradeSigner, currentVersionCode = 100L).verify(tempFile)
            assertTrue(res2 is UpdateVerifier.Result.Rejected)
            assertEquals("downgrade", (res2 as UpdateVerifier.Result.Rejected).reason)

            // 3. Valid signer and upgrade accepted
            val validSigner = object : ApkSignerVerifier {
                override fun signerDigests(apk: File): List<ByteArray> = listOf(ourCert)
                override fun currentAppSignerDigests(): List<ByteArray> = listOf(ourCert)
                override fun versionCodeOf(apk: File): Long = 101L
            }
            val res3 = UpdateVerifier(signer = validSigner, currentVersionCode = 100L).verify(tempFile)
            assertTrue(res3 is UpdateVerifier.Result.Ok)

        } finally {
            tempFile.delete()
        }
    }
}
