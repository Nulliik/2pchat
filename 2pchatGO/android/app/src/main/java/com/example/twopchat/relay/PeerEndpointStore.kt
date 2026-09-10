package com.example.twopchat.relay

import android.content.ContentValues
import android.content.Context
import androidx.annotation.WorkerThread
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.ChatDatabaseHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Call on Dispatchers.IO. One lock serializes read/modify/write operations;
 * SQL transactions also keep migration markers and records consistent. */
internal object PeerEndpointStore {
    private const val TABLE = "peer_endpoint_records"
    private val active = mutableMapOf<String, String>()


    private fun read(db: SQLiteDatabase, fp: String? = null): List<EndpointRecord> = buildList {
        db.query(TABLE, null, if (fp == null) null else "fingerprint = ?",
            fp?.let { arrayOf(it) }, null, null, null).use { cursor ->
            fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
            fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
            while (cursor.moveToNext()) {
                val source = runCatching { EndpointSource.valueOf(text("source")) }.getOrDefault(EndpointSource.DISCOVERY)
                add(EndpointRecord(text("fingerprint"), text("endpoint"), source,
                    number("first_seen"), number("last_seen"), number("last_success"), number("success_days").toInt(),
                    number("last_failure"), number("failures").toInt(), number("retry_after"),
                    number("advertised_expires"), number("saved_contact") != 0L))
            }
        }
    }

    private fun write(db: SQLiteDatabase, rows: List<EndpointRecord>) {
        for (row in rows) {
            val values = ContentValues().apply {
                put("fingerprint", row.fingerprint); put("endpoint", row.endpoint); put("source", row.source.name)
                put("first_seen", row.firstSeen); put("last_seen", row.lastSeen)
                put("last_success", row.lastSuccess); put("success_days", row.successDays)
                put("last_failure", row.lastFailure); put("failures", row.failures); put("retry_after", row.retryAfter)
                put("advertised_expires", row.advertisedExpires); put("saved_contact", if (row.savedContact) 1 else 0)
            }
            check(db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Failed persisting peer route metadata"
            }
        }
    }

    private fun replace(db: SQLiteDatabase, fp: String, rows: List<EndpointRecord>) {
        db.delete(TABLE, "fingerprint = ?", arrayOf(fp))
        write(db, rows)
    }

    private fun savedFriend(context: Context, peerName: String, fp: String, db: SQLiteDatabase): Boolean {
        val prefs = P2PPreferences.prefs(context)
        val chats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
        return chats.contains(peerName) || chats.contains(fp) ||
            chats.any { name ->
                canonicalEndpointFingerprint(P2PPreferences.getPeerFingerprint(context, name)) == fp ||
                canonicalEndpointFingerprint(prefs.getString(P2PPreferences.peerFingerprint(name), null)) == fp
            } ||
            db.rawQuery("SELECT 1 FROM peers WHERE fingerprint = ? OR peer_name = ? LIMIT 1", arrayOf(fp, peerName)).use { it.moveToFirst() } ||
            db.rawQuery("SELECT 1 FROM messages WHERE peer_name = ? LIMIT 1", arrayOf(peerName)).use { it.moveToFirst() }
    }

    private fun importOnce(db: SQLiteDatabase, context: Context, peerName: String, fp: String, now: Long, seeds: Collection<String> = emptyList()) {
        val friend = savedFriend(context, peerName, fp, db)
        db.query("peer_endpoint_imports", arrayOf("fingerprint"), "fingerprint = ?", arrayOf(fp), null, null, null).use {
            if (it.moveToFirst()) {
                if (friend) db.update(TABLE, ContentValues().apply { put("saved_contact", 1) }, "fingerprint = ? AND saved_contact = 0", arrayOf(fp))
                val existing = read(db, fp)
                if (existing.isNotEmpty()) return
            }
        }
        val prefs = P2PPreferences.prefs(context)
        val aliases = linkedSetOf(peerName, fp)
        for ((key, value) in prefs.all) {
            if (key.startsWith("peer_fingerprint_") && value is String && canonicalEndpointFingerprint(value) == fp) {
                aliases.add(key.removePrefix("peer_fingerprint_"))
            }
        }
        val legacy = seeds.toMutableList()
        for (alias in aliases) {
            legacy += prefs.getString(P2PPreferences.lastEndpoint(alias), "").orEmpty().split(',')
            P2PPreferences.getPeerOnionAddress(context, alias)?.let(legacy::add)
        }
        db.query("peers", arrayOf("last_endpoint", "onion_address"), "fingerprint = ? OR peer_name = ?", arrayOf(fp, peerName), null, null, null).use {
            while (it.moveToNext()) {
                if (!it.isNull(0)) legacy += it.getString(0).split(',')
                if (!it.isNull(1)) legacy += it.getString(1)
            }
        }
        val rows = legacy.mapNotNull(EndpointRetention::normalize).distinct().map {
            EndpointRecord(fp, it, EndpointSource.MIGRATED, now, now, savedContact = friend)
        }
        write(db, EndpointRetention.retain(rows, now))
        check(db.insertWithOnConflict("peer_endpoint_imports", null, ContentValues().apply { put("fingerprint", fp) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L)
    }

    @Synchronized @WorkerThread
    fun observe(context: Context, peerName: String, fingerprint: String, endpoints: Collection<String>,
                source: EndpointSource, advertisedExpires: Long = 0, now: Long = System.currentTimeMillis()) {
        val fp = canonicalEndpointFingerprint(fingerprint) ?: return
        val incoming = endpoints.take(16).mapNotNull(EndpointRetention::normalize).distinct()
        if (incoming.isEmpty() || (advertisedExpires > 0 && advertisedExpires <= now)) return
        ChatDatabaseHelper.getInstance(context).endpointTransaction { db ->
            importOnce(db, context, peerName, fp, now)
            val rows = read(db, fp).associateBy { it.endpoint }.toMutableMap()
            val friend = savedFriend(context, peerName, fp, db)
            for (ep in incoming) {
                val old = rows[ep] ?: EndpointRecord(fp, ep, source, now, now, savedContact = friend)
                rows[ep] = old.copy(lastSeen = if (source == EndpointSource.DISCOVERY && old.source != EndpointSource.DISCOVERY) old.lastSeen else now,
                    source = if (source == EndpointSource.DISCOVERY) old.source else source,
                    advertisedExpires = if (source == EndpointSource.DISCOVERY && old.source != EndpointSource.DISCOVERY) old.advertisedExpires else advertisedExpires,
                    savedContact = old.savedContact || friend,
                    retryAfter = if (source == EndpointSource.DISCOVERY) 0L else old.retryAfter)
            }
            replace(db, fp, EndpointRetention.retain(rows.values.toList(), now, setOfNotNull(active[fp])))
            trimIfNeeded(db, now)
        }
    }

    @Synchronized @WorkerThread
    fun resetCooldowns(context: Context, fingerprint: String? = null) {
        ChatDatabaseHelper.getInstance(context).endpointTransaction { db ->
            val values = ContentValues().apply {
                put("failures", 0)
                put("retry_after", 0L)
            }
            if (fingerprint.isNullOrBlank()) {
                db.update(TABLE, values, null, null)
            } else {
                canonicalEndpointFingerprint(fingerprint)?.let { fp ->
                    db.update(TABLE, values, "fingerprint = ?", arrayOf(fp))
                }
            }
        }
    }

    @Synchronized @WorkerThread
    fun result(context: Context, peerName: String, fingerprint: String, endpoint: String, success: Boolean,
               now: Long = System.currentTimeMillis()) {
        val fp = canonicalEndpointFingerprint(fingerprint) ?: return
        val ep = EndpointRetention.normalize(endpoint) ?: return
        ChatDatabaseHelper.getInstance(context).endpointTransaction { db ->
            importOnce(db, context, peerName, fp, now)
            val rows = read(db, fp).associateBy { it.endpoint }.toMutableMap()
            val old = rows[ep] ?: if (success) EndpointRecord(fp, ep, EndpointSource.AUTHENTICATED, now, now,
                savedContact = savedFriend(context, peerName, fp, db)) else return@endpointTransaction
            // JNI callbacks run asynchronously; a delayed failure cannot undo
            // a newer successful connection to this route.
            if (now < maxOf(old.lastSuccess, old.lastFailure) || (!success && now <= old.lastSuccess)) return@endpointTransaction
            rows[ep] = if (success) EndpointRetention.success(old, now) else EndpointRetention.failure(old, now)
            if (success) active[fp] = ep
            replace(db, fp, EndpointRetention.retain(rows.values.toList(), now, setOfNotNull(active[fp])))
            trimIfNeeded(db, now)
        }
    }

    @Synchronized
    fun disconnected(fingerprint: String) { canonicalEndpointFingerprint(fingerprint)?.let(active::remove) }

    @Synchronized @WorkerThread
    fun candidates(context: Context, peerName: String, fingerprint: String, includeReserve: Boolean,
                   now: Long = System.currentTimeMillis(), legacyEndpoints: Collection<String> = emptyList()): List<String> {
        val fp = canonicalEndpointFingerprint(fingerprint) ?: return emptyList()
        return ChatDatabaseHelper.getInstance(context).endpointTransaction { db ->
            importOnce(db, context, peerName, fp, now, legacyEndpoints)
            EndpointRetention.candidates(read(db, fp), now, includeReserve)
        }
    }

    private fun trimIfNeeded(db: SQLiteDatabase, now: Long, force: Boolean = false) {
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst(); it.getLong(0) }
        if (!force && count <= EndpointRetention.MAX_CACHE_RECORDS) return
        val rows = read(db)
        val retained = EndpointRetention.trimCache(rows, now, active.mapValues { setOf(it.value) })
            .map { it.fingerprint to it.endpoint }.toSet()
        rows.filter { (it.fingerprint to it.endpoint) !in retained }.forEach {
            db.delete(TABLE, "fingerprint = ? AND endpoint = ?", arrayOf(it.fingerprint, it.endpoint))
        }
    }

    @Synchronized @WorkerThread
    fun maintain(context: Context, now: Long = System.currentTimeMillis()): Map<String, String> {
        active.keys.removeAll { !com.example.twopchat.NativeBridge.isPeerOnline(it) }
        return ChatDatabaseHelper.getInstance(context).endpointTransaction { db ->
            val identities = P2PPreferences.prefs(context).all.entries.mapNotNull { (key, value) ->
                if (key.startsWith("peer_fingerprint_") && value is String)
                    canonicalEndpointFingerprint(value)?.let { key.removePrefix("peer_fingerprint_") to it } else null
            }.toMutableSet()
            db.rawQuery("SELECT peer_name, fingerprint FROM peers WHERE fingerprint IS NOT NULL", null).use {
                while (it.moveToNext()) canonicalEndpointFingerprint(it.getString(1))?.let { fp -> identities.add(it.getString(0) to fp) }
            }
            for ((name, fp) in identities) importOnce(db, context, name, fp, now)
            trimIfNeeded(db, now, force = true)
            val retained = read(db).groupBy { it.fingerprint }
            val projections = identities.associate { (name, fp) ->
                name to EndpointRetention.candidates(retained[fp].orEmpty(), now, includeReserve = true).joinToString(",")
            }
            // Existing UI readers still consume these bounded projections.
            // Remove expired CSV entries too, without touching contact identity.
            val editor = P2PPreferences.prefs(context).edit()
            for ((name, endpoints) in projections) {
                if (endpoints.isEmpty()) editor.remove(P2PPreferences.lastEndpoint(name))
                else editor.putString(P2PPreferences.lastEndpoint(name), endpoints)
            }
            editor.apply()
            projections
        }
    }

    @Synchronized @WorkerThread
    fun delete(context: Context, fingerprint: String) {
        val fp = canonicalEndpointFingerprint(fingerprint) ?: return
        active.remove(fp)
        ChatDatabaseHelper.getInstance(context).endpointTransaction {
            it.delete(TABLE, "fingerprint = ?", arrayOf(fp))
            // Keep the marker: stale legacy aliases must not resurrect a deleted cache.
        }
    }
}
