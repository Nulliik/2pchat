package com.example.twopchat

import com.example.twopchat.update.AppUpdateManager
import org.junit.Assert.*
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testSemverComparison() {
        assertTrue(AppUpdateManager.isNewerVersion("0.0.8", "0.0.9"))
        assertTrue(AppUpdateManager.isNewerVersion("0.0.8", "v0.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("0.0.8", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("0.0.8", "0.0.8.1"))

        assertFalse(AppUpdateManager.isNewerVersion("0.0.8", "0.0.8"))
        assertFalse(AppUpdateManager.isNewerVersion("0.0.8", "v0.0.8"))
        assertFalse(AppUpdateManager.isNewerVersion("0.0.8", "0.0.7"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test
    fun testParseGitHubReleaseJsonWithApk() {
        val sampleJson = """
            {
                "tag_name": "v0.0.9",
                "name": "2PChat v0.0.9 Update",
                "body": "## What's Changed\n* Added in-app updater\n* Bug fixes",
                "published_at": "2026-08-31T20:00:00Z",
                "assets": [
                    {
                        "name": "source.tar.gz",
                        "size": 10240,
                        "browser_download_url": "https://github.com/Nulliik/2pchat/releases/download/v0.0.9/source.tar.gz"
                    },
                    {
                        "name": "2pchat-v0.0.9.apk",
                        "size": 25485760,
                        "browser_download_url": "https://github.com/Nulliik/2pchat/releases/download/v0.0.9/2pchat-v0.0.9.apk"
                    }
                ]
            }
        """.trimIndent()

        val release = AppUpdateManager.parseGitHubReleaseJson(sampleJson)
        assertNotNull(release)
        assertEquals("0.0.9", release?.versionName)
        assertEquals("v0.0.9", release?.tagName)
        assertEquals("2PChat v0.0.9 Update", release?.title)
        assertEquals("https://github.com/Nulliik/2pchat/releases/download/v0.0.9/2pchat-v0.0.9.apk", release?.apkUrl)
        assertEquals(25485760L, release?.apkSizeBytes)
        assertTrue(release?.changelog?.contains("Added in-app updater") == true)
    }

    @Test
    fun testParseGitHubReleaseJsonWithoutApk() {
        val sampleJson = """
            {
                "tag_name": "v0.0.9",
                "name": "2PChat v0.0.9",
                "body": "No APK attached",
                "assets": [
                    {
                        "name": "readme.txt",
                        "size": 128,
                        "browser_download_url": "https://github.com/Nulliik/2pchat/releases/download/v0.0.9/readme.txt"
                    }
                ]
            }
        """.trimIndent()

        val release = AppUpdateManager.parseGitHubReleaseJson(sampleJson)
        assertNull(release)
    }

    @Test
    fun testParseMalformedJson() {
        val release = AppUpdateManager.parseGitHubReleaseJson("{ invalid json }")
        assertNull(release)
    }

    @Test
    fun testReleaseInfoSerializationRoundtrip() {
        val original = com.example.twopchat.update.ReleaseInfo(
            versionName = "0.0.8.4",
            tagName = "v0.0.8.4",
            title = "Release v0.0.8.4",
            changelog = "Bug fixes and improvements",
            apkUrl = "https://github.com/kodzyfox/2pchat-releases/releases/download/v0.0.8.4/2pchat-update.apk",
            apkSizeBytes = 73400320L,
            publishedAt = "2026-09-01T05:00:00Z"
        )
        val jsonStr = original.toJsonString()
        val parsed = AppUpdateManager.parseGitHubReleaseJson(jsonStr)
        assertNotNull(parsed)
        assertEquals("0.0.8.4", parsed?.versionName)
        assertEquals("v0.0.8.4", parsed?.tagName)
        assertEquals("Release v0.0.8.4", parsed?.title)
        assertEquals(73400320L, parsed?.apkSizeBytes)
    }
}
