package com.example.twopchat.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.twopchat.relay.NetworkTrafficStats
import com.example.twopchat.relay.TrafficCategory
import com.example.twopchat.relay.TrafficDirection
import com.example.twopchat.relay.TrafficProtocol
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NetworkUsagePageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NetworkTrafficStats.reset(context)
        NetworkTrafficStats.record(
            context,
            TrafficProtocol.DIRECT_P2P,
            TrafficCategory.MESSAGES,
            TrafficDirection.SENT,
            bytes = 1_024L,
        )
        NetworkTrafficStats.record(
            context,
            TrafficProtocol.YGGDRASIL,
            TrafficCategory.STICKERS,
            TrafficDirection.RECEIVED,
            bytes = 2_048L,
        )
        composeRule.setContent {
            NetworkUsagePage(
                appLanguage = "English",
                surfaceColor = Color(0xFF202124),
                onSurfaceColor = Color.White,
                onSurfaceVariant = Color.LightGray,
                primaryColor = Color.Cyan,
                onBackClick = {},
            )
        }
    }

    @After
    fun tearDown() {
        NetworkTrafficStats.reset(
            ApplicationProvider.getApplicationContext(),
        )
    }

    @Test
    fun showsTrafficSplitByProtocolAndCategory() {
        composeRule.onNodeWithText("Network Usage").assertExists()
        composeRule.onNodeWithText("Direct P2P").assertExists()
        composeRule.onNodeWithText("Yggdrasil P2P").assertExists()
        composeRule.onNodeWithText("Messages").assertExists()
        composeRule.onNodeWithText("Stickers").assertExists()
        composeRule.onNodeWithText("3.0 KB").assertExists()
    }
}
