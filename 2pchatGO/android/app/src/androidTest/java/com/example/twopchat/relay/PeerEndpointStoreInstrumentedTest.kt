package com.example.twopchat.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.ChatDatabaseHelper
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerEndpointStoreInstrumentedTest {
    @Test fun sqlCipherMigrationReservesAndSuccessRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "endpoint-test-${UUID.randomUUID()}"
        // Match crypto.Fingerprint in the native core, including case-sensitive Base64.
        val fp = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(name.toByteArray()), android.util.Base64.NO_WRAP)
        val prefs = P2PPreferences.prefs(context)
        val onion = "${"b".repeat(56)}.onion:50001"
        val direct = "8.8.8.8:50001"
        val now = System.currentTimeMillis()
        prefs.edit().putString(P2PPreferences.peerFingerprint(name), fp)
            .putString(P2PPreferences.lastEndpoint(name), "$direct,$onion")
            .putStringSet("active_chats", prefs.getStringSet("active_chats", emptySet()).orEmpty() + name).commit()
        try {
            assertTrue("Fresh Go/JNI library must load with the endpoint-result callback", NativeBridge.isLoaded)
            assertEquals(2, PeerEndpointStore.candidates(context, name, fp, false, now).size)
            prefs.edit().remove(P2PPreferences.lastEndpoint(name)).commit()
            assertEquals("Routes survive removal of the legacy projection", 2,
                PeerEndpointStore.candidates(context, name, fp, false, now).size)
            val db = ChatDatabaseHelper.getInstance(context)
            db.endpointTransaction { sql ->
                sql.rawQuery("SELECT MAX(last_success),MAX(success_days) FROM peer_endpoint_records WHERE fingerprint = ?", arrayOf(fp)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertEquals(0, it.getInt(1))
                }
            }
            val future = now + 400 * EndpointRetention.DAY
            assertTrue(PeerEndpointStore.candidates(context, name, fp, false, future).isEmpty())
            assertEquals(listOf(onion), PeerEndpointStore.candidates(context, name, fp, true, future))
            PeerEndpointStore.result(context, name, fp, onion, true, future)
            PeerEndpointStore.disconnected(fp)
            assertEquals(listOf(onion), PeerEndpointStore.candidates(context, name, fp, false, future))
            // Old callback delivery cannot turn a successful route into a failed one.
            PeerEndpointStore.result(context, name, fp, onion, false, future - 1)
            db.endpointTransaction { sql ->
                sql.rawQuery("SELECT last_success,success_days,failures FROM peer_endpoint_records WHERE fingerprint = ? AND endpoint = ?", arrayOf(fp, onion)).use {
                    assertTrue(it.moveToFirst()); assertEquals(future, it.getLong(0))
                    assertEquals(1, it.getInt(1)); assertEquals(0, it.getInt(2))
                }
            }
            assertTrue(prefs.getStringSet("active_chats", emptySet()).orEmpty().contains(name))
            assertEquals(fp, prefs.getString(P2PPreferences.peerFingerprint(name), null))
            PeerEndpointStore.delete(context, fp)
            prefs.edit().putString(P2PPreferences.lastEndpoint(name), "$direct,$onion").commit()
            assertTrue("A completed import must not resurrect deleted routes from stale aliases",
                PeerEndpointStore.candidates(context, name, fp, true, future + 1).isEmpty())
        } finally {
            PeerEndpointStore.disconnected(fp)
            ChatDatabaseHelper.getInstance(context).endpointTransaction { sql ->
                sql.delete("peer_endpoint_records", "fingerprint = ?", arrayOf(fp))
                sql.delete("peer_endpoint_imports", "fingerprint = ?", arrayOf(fp))
            }
            prefs.edit().remove(P2PPreferences.peerFingerprint(name)).remove(P2PPreferences.lastEndpoint(name))
                .putStringSet("active_chats", prefs.getStringSet("active_chats", emptySet()).orEmpty() - name).commit()
        }
    }
}
