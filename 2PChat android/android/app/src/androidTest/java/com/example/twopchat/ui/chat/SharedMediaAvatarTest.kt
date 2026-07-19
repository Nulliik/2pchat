package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.twopchat.P2PMessageRelay
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SharedMediaAvatarTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var avatar: Bitmap

    @Before
    fun setAvatar() {
        avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        P2PMessageRelay.peerAvatars["Alice"] = avatar
    }

    @After
    fun clearAvatar() {
        P2PMessageRelay.peerAvatars.remove("Alice")
        avatar.recycle()
    }

    @Test
    fun profileAvatarInvokesFullscreenCallback() {
        var opened = false
        composeTestRule.setContent {
            SharedMediaScreen(
                peerName = "Alice",
                messages = emptyList(),
                primaryColor = Color.Cyan,
                surfaceColor = Color.Black,
                onSurfaceColor = Color.White,
                onSurfaceVariant = Color.LightGray,
                appLanguage = "English",
                isVerified = false,
                isMuted = false,
                onToggleMute = {},
                onAvatarClick = { opened = it === avatar },
                onImageClick = { _, _ -> },
                onVideoClick = {},
                onBack = {},
                onNavigateToMessage = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Avatar").performClick()
        composeTestRule.runOnIdle { assertTrue(opened) }
    }
}
