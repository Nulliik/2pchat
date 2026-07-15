package com.example.twopchat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenViewModelTest {
    @Test
    fun initialLoadRunsOnlyOnceForConfigurationStablePeerState() {
        val viewModel = ChatScreenViewModel()

        assertTrue(viewModel.beginInitialLoad("Alice"))
        viewModel.messages += Message("1", "hello", false, "12:00")

        assertFalse(viewModel.beginInitialLoad("Alice"))
        assertTrue(viewModel.messages.any { it.id == "1" })
    }
}
