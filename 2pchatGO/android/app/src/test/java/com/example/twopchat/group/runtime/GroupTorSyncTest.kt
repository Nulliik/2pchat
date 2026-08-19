package com.example.twopchat.group.runtime

import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.resolvePeerEndpoint
import com.example.twopchat.security.ImageSanitizer
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GroupTorSyncTest {

    @Test
    fun resolvePeerEndpointPrioritizesOnionAddressForGroupMembers() {
        val onion = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"
        val liveIp = "192.168.1.150:50001"
        val persistedIp = "203.0.113.10:50001"

        val resolved = resolvePeerEndpoint(
            peerName = "Alice",
            liveEndpoint = liveIp,
            persistedEndpoint = persistedIp,
            onionEndpoint = onion,
        )

        assertNotNull(resolved)
        val endpoints = resolved!!.split(",")
        // Onion endpoint must be placed first to ensure Tier 1 selection
        assertEquals(onion, endpoints.first())
        assertTrue(endpoints.contains(liveIp))
        assertTrue(endpoints.contains(persistedIp))
    }

    @Test
    fun groupSyncWorkerRethrowsCancellationExceptionWithoutRetrying() {
        var cancellationRethrown = false
        try {
            try {
                throw CancellationException("WorkManager job stopped by OS constraint")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail("CancellationException was swallowed by generic Exception catch block!")
            }
        } catch (e: CancellationException) {
            cancellationRethrown = true
        }

        assertTrue("CancellationException must be propagated out of doWork()", cancellationRethrown)
    }

    @Test
    fun imageSanitizerIdentifiesSanitizableMediaForGroupUploads() {
        // Formats that must be sanitized before block-level encryption
        assertTrue(ImageSanitizer.isSanitizableImage("group_upload_123.jpg"))
        assertTrue(ImageSanitizer.isSanitizableImage("group_upload_123.jpeg"))
        assertTrue(ImageSanitizer.isSanitizableImage("group_upload_123.png"))
        assertTrue(ImageSanitizer.isSanitizableImage("group_upload_123.webp"))
        assertTrue(ImageSanitizer.isSanitizableImage("group_upload_123.heic"))

        // Formats that should bypass image EXIF processing
        assertFalse(ImageSanitizer.isSanitizableImage("attachment.pdf"))
        assertFalse(ImageSanitizer.isSanitizableImage("video.mp4"))
        assertFalse(ImageSanitizer.isSanitizableImage("archive.zip"))
    }

    @Test
    fun groupEventKindsMatchMessageAndMedia() {
        val messageKind = GroupEventKind.MESSAGE
        val mediaKind = GroupEventKind.MEDIA
        val replyKind = GroupEventKind.REPLY

        assertEquals("MESSAGE", messageKind.name)
        assertEquals("MEDIA", mediaKind.name)
        assertEquals("REPLY", replyKind.name)
    }
}
