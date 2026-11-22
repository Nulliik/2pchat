package com.example.twopchat.ui.chat

import com.example.twopchat.ui.chat.state.ChatHistoryCache
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryCacheTest {
    @Test
    fun invalidatingPeerAndAliasesPreventsStaleAsyncCacheFill() {
        val peer = "cache-test-peer"
        val alias = "cache-test-fingerprint"
        val messages = listOf(Message("cache-test-id", "deleted", false, "12:00"))
        try {
            val generation = ChatHistoryCache.generation(peer)
            ChatHistoryCache.put(peer, messages)
            ChatHistoryCache.put(alias, messages)

            ChatHistoryCache.invalidate(listOf(peer, alias))

            assertNull(ChatHistoryCache.get(peer))
            assertNull(ChatHistoryCache.get(alias))
            assertFalse(ChatHistoryCache.putIfUnchanged(peer, messages, generation))
            assertNull(ChatHistoryCache.get(peer))
        } finally {
            ChatHistoryCache.remove(peer)
            ChatHistoryCache.remove(alias)
        }
    }

    @Test
    fun clearHistoryWaitsForOutstandingReloadBeforeDeleting() = runBlocking {
        val peer = "cache-test-race"
        val messages = listOf(Message("cache-test-race-id", "deleted", false, "12:00"))
        ChatHistoryCache.remove(peer)
        val readStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val reload = async {
                ChatHistoryCache.withHistoryAccess {
                    readStarted.complete(Unit)
                    releaseRead.await()
                    ChatHistoryCache.put(peer, messages)
                }
            }
            readStarted.await()
            var deleted = false
            val clear = async {
                ChatHistoryCache.clearHistory(peer, emptyList()) { deleted = true }
            }
            kotlinx.coroutines.yield()
            assertFalse(deleted)
            releaseRead.complete(Unit)
            reload.await()
            clear.await()
            assertTrue(deleted)
            assertNull(ChatHistoryCache.get(peer))
        } finally {
            ChatHistoryCache.remove(peer)
        }
    }
}
