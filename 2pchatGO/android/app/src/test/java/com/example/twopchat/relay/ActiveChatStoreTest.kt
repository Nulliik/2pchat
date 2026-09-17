package com.example.twopchat.relay

import com.example.twopchat.config.Bug18Call
import com.example.twopchat.config.Bug18Gate
import com.example.twopchat.config.Bug18Prefs
import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveChatStoreTest {
    @Before
    fun reset() = LastMessagePreviewStore.clearSensitiveMemory()

    @After
    fun clear() = LastMessagePreviewStore.clearSensitiveMemory()

    @Test
    fun concurrentAddsReadInsideTheSameBoundary() {
        val store = Bug18Prefs()
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == "active_chats") gate.pause() }
            val first = Bug18Call { ActiveChatStore.add(store.prefs, "A") }
            gate.awaitEntry()
            val second = Bug18Call { ActiveChatStore.add(store.prefs, "B") }
            try {
                second.awaitBlocked()
            } finally {
                gate.close()
            }
            first.result()
            second.result()
            assertEquals(setOf("A", "B"), ActiveChatStore.snapshot(store.prefs))
        }
    }

    @Test
    fun removalDoesNotLoseConcurrentUnrelatedAddOrReappearOnRestore() {
        val store = Bug18Prefs()
        ActiveChatStore.add(store.prefs, "Peer")
        val restoreRevision = ActiveChatStore.revision()
        Bug18Gate().use { gate ->
            val removal = Bug18Call {
                ActiveChatStore.update(store.prefs, { chats -> gate.pause(); chats - "Peer" })
            }
            gate.awaitEntry()
            val addition = Bug18Call { ActiveChatStore.add(store.prefs, "Other") }
            try {
                addition.awaitBlocked()
            } finally {
                gate.close()
            }
            removal.result()
            addition.result()
        }
        assertEquals(setOf("Other"), ActiveChatStore.mergeIfUnchanged(store.prefs, restoreRevision, setOf("Peer")))
        ActiveChatStore.add(store.prefs, "Peer")
        assertEquals(setOf("Other", "Peer"), ActiveChatStore.snapshot(store.prefs))
    }

    @Test
    fun noOpSanitizeDoesNotDiscardHistoryRestore() {
        val prefs = Bug18Prefs().prefs
        ActiveChatStore.add(prefs, "Live")
        val revision = ActiveChatStore.revision()
        ActiveChatStore.update(prefs, { it.filter(String::isNotBlank).toSet() })
        assertEquals(revision, ActiveChatStore.revision())
        assertEquals(
            setOf("Live", "History"),
            ActiveChatStore.mergeIfUnchanged(prefs, revision, setOf("History")),
        )
    }

    @Test
    fun closedAccountRejectsReplacementPreferencesAndOldTasksAfterReopen() {
        val prefs = Bug18Prefs().prefs
        val task = checkNotNull(ActiveChatStore.admit(prefs, "Peer"))
        val replacement = Bug18Prefs().prefs
        try {
            ActiveChatStore.closeAccount()
            assertNull(ActiveChatStore.admit(replacement, "Peer"))
            ActiveChatStore.add(replacement, "Peer")
            assertEquals(emptySet<String>(), ActiveChatStore.snapshot(replacement))
            assertNull(ActiveChatStore.resolve(task))
            ActiveChatStore.openAccount()
            assertNull(ActiveChatStore.resolve(task))
            val fresh = checkNotNull(ActiveChatStore.admit(replacement, "New"))
            assertEquals("New", ActiveChatStore.resolve(fresh))
        } finally {
            ActiveChatStore.openAccount()
        }
    }

    @Test
    fun renameOfRemovedChatDoesNotCreateNewChat() {
        val prefs = Bug18Prefs().prefs
        ActiveChatStore.add(prefs, "Other")
        ActiveChatStore.update(prefs, { if ("Old" in it) it - "Old" + "New" else it })
        assertEquals(setOf("Other"), ActiveChatStore.snapshot(prefs))
    }

    @Test
    fun wipeWaitsForWriterAndRetiredInstanceCannotWriteAgain() {
        val store = Bug18Prefs()
        Bug18Gate().use { gate ->
            val writer = Bug18Call {
                ActiveChatStore.update(store.prefs, { gate.pause(); it + "Old" })
            }
            gate.awaitEntry()
            val wipe = Bug18Call {
                synchronized(P2PPreferences) {
                    ActiveChatStore.retire(store.prefs)
                    store.prefs.edit().clear().commit()
                    LastMessagePreviewStore.clearSensitiveMemory()
                }
            }
            try {
                wipe.awaitBlocked()
            } finally {
                gate.close()
            }
            writer.result()
            wipe.result()
        }
        ActiveChatStore.add(store.prefs, "Ghost")
        assertEquals(emptySet<String>(), ActiveChatStore.snapshot(store.prefs))
        val fresh = Bug18Prefs().prefs
        ActiveChatStore.add(fresh, "New")
        assertEquals(setOf("New"), ActiveChatStore.snapshot(fresh))
    }

    @Test
    fun sanitizeUsesCurrentSnapshotAndKeepsAdditionalEditsInOnePublish() {
        val store = Bug18Prefs()
        store.prefs.edit().putStringSet("active_chats", setOf("", "Keep")).apply()
        ActiveChatStore.update(store.prefs, { it.filter(String::isNotBlank).toSet() }) {
            putString("transport_Keep", "DIRECT P2P")
        }
        assertEquals(setOf("Keep"), ActiveChatStore.snapshot(store.prefs))
        assertEquals("DIRECT P2P", store.prefs.getString("transport_Keep", null))
    }

    @Test
    fun staleListenerCannotOverwriteNewPublish() {
        val prefs = Bug18Prefs().prefs
        prefs.edit().putString("last_msg_Peer", "old").apply()
        P2PPreferences.lastMessageCache["Peer"] = "old"
        Bug18Gate().use { gate ->
            val listener = Bug18Call {
                LastMessagePreviewStore.refresh(prefs, "Peer") { gate.pause(); it }
            }
            gate.awaitEntry()
            LastMessagePreviewStore.publish("Peer", "new")
            gate.close()
            listener.result()
        }
        assertEquals("new", LastMessagePreviewStore.get(prefs, "Peer"))
    }

    @Test
    fun staleColdLoadReturnsNewPublishInsteadOfInstallingOldValue() {
        val prefs = Bug18Prefs().prefs
        prefs.edit().putString("last_msg_Peer", "old").apply()
        Bug18Gate().use { gate ->
            val reader = Bug18Call {
                LastMessagePreviewStore.get(prefs, "Peer") { gate.pause(); it }
            }
            gate.awaitEntry()
            LastMessagePreviewStore.publish("Peer", "new")
            gate.close()
            assertEquals("new", reader.result())
        }
    }

    @Test
    fun listenerCannotRepopulateAfterWipe() {
        val prefs = Bug18Prefs().prefs
        prefs.edit().putString("last_msg_Peer", "secret").apply()
        Bug18Gate().use { gate ->
            val listener = Bug18Call {
                LastMessagePreviewStore.refresh(prefs, "Peer") { gate.pause(); it }
            }
            gate.awaitEntry()
            LastMessagePreviewStore.clearSensitiveMemory()
            prefs.edit().clear().commit()
            gate.close()
            listener.result()
        }
        assertNull(P2PPreferences.lastMessageCache["Peer"])
    }

    @Test
    fun olderPersistenceCannotReplaceNewerPublication() {
        val prefs = Bug18Prefs().prefs
        val old = LastMessagePreviewStore.publish("Peer", "old")
        Bug18Gate().use { gate ->
            val writer = Bug18Call {
                LastMessagePreviewStore.persist(prefs, "Peer", old) { gate.pause(); it }
            }
            gate.awaitEntry()
            val newer = LastMessagePreviewStore.publish("Peer", "new")
            LastMessagePreviewStore.persist(prefs, "Peer", newer) { it }
            gate.close()
            writer.result()
        }
        assertEquals("new", prefs.getString("last_msg_Peer", null))
        assertEquals("new", LastMessagePreviewStore.get(prefs, "Peer"))
    }

    @Test
    fun movedPendingWriteUsesNewNameAndDoesNotReplaceNewerTarget() {
        val prefs = Bug18Prefs().prefs
        val update = LastMessagePreviewStore.publish("Old", "pending")
        LastMessagePreviewStore.move("Old", "New")
        LastMessagePreviewStore.persist(prefs, "Old", update) { it }
        assertNull(prefs.getString("last_msg_Old", null))
        assertEquals("pending", prefs.getString("last_msg_New", null))
        val other = LastMessagePreviewStore.publish("Old", "older")
        LastMessagePreviewStore.publish("New", "newer")
        LastMessagePreviewStore.move("Old", "New")
        LastMessagePreviewStore.persist(prefs, "Old", other) { it }
        assertEquals("newer", LastMessagePreviewStore.get(prefs, "New"))
    }

    @Test
    fun moveRetainsStaleDestinationRevision() {
        val prefs = Bug18Prefs().prefs
        val stale = LastMessagePreviewStore.publish("New", "stale")
        val fresh = LastMessagePreviewStore.publish("Old", "fresh")
        LastMessagePreviewStore.persist(prefs, "New", stale) { it }
        LastMessagePreviewStore.move("Old", "New")
        LastMessagePreviewStore.persist(prefs, "Old", fresh) { it }
        assertEquals("fresh", LastMessagePreviewStore.get(prefs, "New"))
        assertNull(prefs.getString("last_msg_Old", null))
    }
    @Test
    fun deleteClearsPreviewAndRejectsDelayedPersistence() {
        val prefs = Bug18Prefs().prefs
        ActiveChatStore.add(prefs, "Peer")
        val update = LastMessagePreviewStore.publish("Peer", "sent")
        LastMessagePreviewStore.persist(prefs, "Peer", update) { it }
        val delayed = LastMessagePreviewStore.publish("Peer", "queued")
        ActiveChatStore.beginDelete(prefs, listOf("Peer"))
        ActiveChatStore.update(prefs, { it - "Peer" }) {
            LastMessagePreviewStore.remove("Peer")
            remove("last_msg_Peer")
        }
        LastMessagePreviewStore.persist(prefs, "Peer", delayed) { it }
        assertNull(ActiveChatStore.admit(prefs, "Peer"))
        ActiveChatStore.completeDelete(prefs, listOf("Peer"))
        assertEquals(emptySet<String>(), ActiveChatStore.snapshot(prefs))
        assertNull(LastMessagePreviewStore.get(prefs, "Peer") { it })
        assertNull(prefs.getString("last_msg_Peer", null))
    }

    @Test
    fun cancellationDuringTransformDoesNotPublish() {
        val prefs = Bug18Prefs().prefs
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        try {
            ActiveChatStore.update(prefs, { throw cancelled })
            org.junit.Assert.fail("Cancellation was swallowed")
        } catch (actual: java.util.concurrent.CancellationException) {
            org.junit.Assert.assertSame(cancelled, actual)
        }
        assertEquals(emptySet<String>(), ActiveChatStore.snapshot(prefs))
    }

    @Test
    fun cancellationDuringPreviewLoadDoesNotPopulateCache() {
        val prefs = Bug18Prefs().prefs
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        try {
            LastMessagePreviewStore.refresh(prefs, "Peer") { throw cancelled }
            org.junit.Assert.fail("Cancellation was swallowed")
        } catch (actual: java.util.concurrent.CancellationException) {
            org.junit.Assert.assertSame(cancelled, actual)
        }
        assertNull(P2PPreferences.lastMessageCache["Peer"])
    }

    @Test
    fun coldLoadAndRemovalAreReflectedWithoutCachingPlaceholder() {
        val prefs = Bug18Prefs().prefs
        assertNull(LastMessagePreviewStore.get(prefs, "Peer") { it })
        prefs.edit().putString("last_msg_Peer", "stored").apply()
        assertEquals("stored", LastMessagePreviewStore.get(prefs, "Peer") { it })
        prefs.edit().remove("last_msg_Peer").apply()
        LastMessagePreviewStore.refresh(prefs, "Peer") { it }
        assertNull(P2PPreferences.lastMessageCache["Peer"])
    }

    @Test
    fun queuedTaskFollowsRenameAndDiesWithDelete() {
        val prefs = Bug18Prefs().prefs
        ActiveChatStore.add(prefs, "Old")
        val task = ActiveChatStore.admit(prefs, "Old")
        ActiveChatStore.redirect(prefs, "Old", "New")
        assertEquals("New", ActiveChatStore.resolve(checkNotNull(task)))
        ActiveChatStore.beginDelete(prefs, listOf("New"))
        assertNull(ActiveChatStore.resolve(task))
        ActiveChatStore.completeDelete(prefs, listOf("New"))
        assertNull(ActiveChatStore.resolve(task))
    }

    @Test
    fun queuedTaskAdmittedBeforeDeleteIsDroppedEvenAfterRedirect() {
        val prefs = Bug18Prefs().prefs
        val task = ActiveChatStore.admit(prefs, "Old")
        ActiveChatStore.redirect(prefs, "Old", "New")
        assertEquals("New", ActiveChatStore.resolve(checkNotNull(task)))
        ActiveChatStore.beginDelete(prefs, listOf("Old"))
        assertNull(ActiveChatStore.resolve(checkNotNull(task)))
    }
}
