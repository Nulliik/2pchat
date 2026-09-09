package com.example.twopchat

import com.example.twopchat.config.P2PPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerChatNameValidationTest {

    @Test
    fun testValidPeerNames() {
        assertTrue(P2PPreferences.isValidPeerChatName("Foxxxy"))
        assertTrue(P2PPreferences.isValidPeerChatName("puppy"))
        assertTrue(P2PPreferences.isValidPeerChatName("4mo"))
        assertTrue(P2PPreferences.isValidPeerChatName("popa"))
        assertTrue(P2PPreferences.isValidPeerChatName("Alice Cooper"))
        assertTrue(P2PPreferences.isValidPeerChatName("User_123"))
    }

    @Test
    fun testRejectsInternalWallpaperAndPreferencePrefixes() {
        assertFalse(P2PPreferences.isValidPeerChatName("blur_4mo"))
        assertFalse(P2PPreferences.isValidPeerChatName("blur_dzPld8GVZuNv3LnrjUa"))
        assertFalse(P2PPreferences.isValidPeerChatName("blur_puppy"))
        assertFalse(P2PPreferences.isValidPeerChatName("dimming_4mo"))
        assertFalse(P2PPreferences.isValidPeerChatName("dimming_dzPld8GVZuNv3L"))
        assertFalse(P2PPreferences.isValidPeerChatName("dimming_popa"))
        assertFalse(P2PPreferences.isValidPeerChatName("dimming_puppy"))
        assertFalse(P2PPreferences.isValidPeerChatName("motion_popa"))
        assertFalse(P2PPreferences.isValidPeerChatName("direct_wallpaper_blur_4mo"))
        assertFalse(P2PPreferences.isValidPeerChatName("group_wallpaper_dimming_123"))
        assertFalse(P2PPreferences.isValidPeerChatName("my_profile_about_me"))
    }

    @Test
    fun testRejectsRawFingerprintsAndSpecialPaths() {
        assertFalse(P2PPreferences.isValidPeerChatName("68ULnWLcY78q7ufwniR/"))
        assertFalse(P2PPreferences.isValidPeerChatName("path/to/something"))
        assertFalse(P2PPreferences.isValidPeerChatName("dzPld8GVZuNv3LnrjUaHnD=="))
    }

    @Test
    fun testRejectsSpecialSavedMessagesAndNulls() {
        assertFalse(P2PPreferences.isValidPeerChatName("Saved Messages"))
        assertFalse(P2PPreferences.isValidPeerChatName("Сохраненное"))
        assertFalse(P2PPreferences.isValidPeerChatName("Избранное"))
        assertFalse(P2PPreferences.isValidPeerChatName("null"))
        assertFalse(P2PPreferences.isValidPeerChatName("NULL"))
        assertFalse(P2PPreferences.isValidPeerChatName(""))
        assertFalse(P2PPreferences.isValidPeerChatName("   "))
        assertFalse(P2PPreferences.isValidPeerChatName(null))
    }
}
