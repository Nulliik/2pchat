package com.example.twopchat.group.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteWallpaperSecurityTest {

    private val sampleMember = GroupInviteMember(
        fingerprint = "owner-fp-1",
        peerName = "Alice",
        deviceId = "device-1",
        signingKey = "signing-key-1",
        role = "OWNER",
        status = "ACTIVE",
    )

    private fun createBaseInvite(
        wallpaperDataB64: String? = null,
        wallpaperSigned: Boolean = false,
        avatarDataB64: String? = null,
        avatarSigned: Boolean = false,
    ): GroupInvite {
        return GroupInvite(
            inviteId = "invite-123",
            groupId = "group-abc",
            title = "Secret Society",
            description = "Encrypted private group",
            epoch = 1L,
            epochSecretBase64 = "ZXBvY2gtc2VjcmV0LW1hdGVyaWFs",
            ownerFingerprint = "owner-fp-1",
            senderFingerprint = "owner-fp-1",
            senderSigningKey = "signing-key-1",
            coordinatorFingerprint = "owner-fp-1",
            controlHead = "ctrl-head-1",
            historyCursors = mapOf("device-1" to 10L),
            createdAtMs = 1_700_000_000_000L,
            rosterSize = 1,
            members = listOf(sampleMember),
            cryptoSuite = "epoch-aead-chacha20poly1305-v1",
            signatureBase64 = "c2lnbmF0dXJl",
            adminOnlyPosting = false,
            groupAvatarDataB64 = avatarDataB64,
            groupAvatarSigned = avatarSigned,
            groupWallpaperDataB64 = wallpaperDataB64,
            groupWallpaperSigned = wallpaperSigned,
        )
    }

    @Test
    fun canonicalStringIncludesWallpaperWhenSigned() {
        val wallpaperData = "AQIDBAU="
        val inviteWithWallpaper = createBaseInvite(
            wallpaperDataB64 = wallpaperData,
            wallpaperSigned = true,
        )
        val canonical = inviteWithWallpaper.canonicalForSignature()

        assertTrue(
            "Canonical signature payload must declare signed wallpaper v1",
            canonical.contains("group_wallpaper_signed=v1\n"),
        )
        assertTrue(
            "Canonical signature payload must include wallpaper data",
            canonical.contains("group_wallpaper_data=$wallpaperData\n"),
        )
    }

    @Test
    fun canonicalStringOmitsWallpaperWhenUnsignedOrAbsent() {
        val unsignedInvite = createBaseInvite(
            wallpaperDataB64 = "AQIDBAU=",
            wallpaperSigned = false,
        )
        assertFalse(unsignedInvite.canonicalForSignature().contains("group_wallpaper_signed"))
        assertFalse(unsignedInvite.canonicalForSignature().contains("group_wallpaper_data"))

        val absentInvite = createBaseInvite(
            wallpaperDataB64 = null,
            wallpaperSigned = false,
        )
        assertFalse(absentInvite.canonicalForSignature().contains("group_wallpaper_signed"))
        assertFalse(absentInvite.canonicalForSignature().contains("group_wallpaper_data"))
    }

    @Test
    fun tamperingWallpaperDataChangesCanonicalSignaturePayload() {
        val original = createBaseInvite(
            wallpaperDataB64 = "AQIDBAU=",
            wallpaperSigned = true,
        )
        val tampered = original.copy(groupWallpaperDataB64 = "dGFtcGVyZWQ=")

        assertNotEquals(
            "Tampered wallpaper must change canonical signature payload so Ed25519 signature fails",
            original.canonicalForSignature(),
            tampered.canonicalForSignature(),
        )
    }

    @Test
    fun canonicalSigningRequiresWallpaperDataWhenSignedFlagIsTrue() {
        val invalidSignedInvite = createBaseInvite(
            wallpaperDataB64 = null,
            wallpaperSigned = true,
        )

        assertThrows(IllegalArgumentException::class.java) {
            invalidSignedInvite.canonicalForSignature()
        }
    }

    @Test
    fun wallpaperLimitsAndBase64CapacityAreStrictlyConsistent() {
        assertEquals(500_000, GroupWireProtocol.MAX_GROUP_WALLPAPER_BYTES)
        assertEquals(666_668, GroupWireProtocol.MAX_GROUP_WALLPAPER_BASE64_CHARS)

        val maxBase64CharsForBytes = ((GroupWireProtocol.MAX_GROUP_WALLPAPER_BYTES + 2) / 3) * 4
        assertTrue(
            "Base64 char bound must accommodate max wallpaper binary bytes",
            maxBase64CharsForBytes <= GroupWireProtocol.MAX_GROUP_WALLPAPER_BASE64_CHARS,
        )
    }
}
