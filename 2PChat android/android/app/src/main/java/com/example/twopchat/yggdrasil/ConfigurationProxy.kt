package com.example.twopchat.yggdrasil

import android.content.Context
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

// БАГ 1 ИСПРАВЛЕН: Был object (singleton с race condition).
// Теперь это обычный класс — каждый экземпляр владеет своим файлом/json и не конкурирует с другими.
class ConfigurationProxy(applicationContext: Context) {
    private companion object {
        private const val PREFS = "2pchat_prefs"
        private const val PREF_POOL_SEEDED = "yggdrasil_public_pool_seeded_v1"
        private const val PREF_POOL_PRUNED = "yggdrasil_public_pool_pruned_v1"
        private const val MAX_RETAINED_PUBLIC_PEERS = 6
        // Bootstrap peers taken from the official public-peers repository on
        // 2026-07-09.  They are intentionally nearby (Russia/Finland) and use
        // TCP/TLS, which are supported by the bundled Android library.
        val DEFAULT_PUBLIC_PEERS = listOf(
            "tls://ygg-msk-1.averyan.ru:8362",
            "tls://yggno.de:18227",
            "tcp://89.44.86.85:65535",
            "tls://45.95.202.21:443",
            "tls://95.217.35.92:1337",
        )

        // Offline snapshot of every TCP/TLS endpoint marked online by the
        // official public-peer monitor on 2026-07-09. It is compressed to keep
        // the APK small and never fetched from the network at runtime.
        private const val PUBLIC_PEER_SNAPSHOT_GZIP_BASE64 = "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"
    }
    private var json: JSONObject
    private val file: File
    private val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        file = File(applicationContext.filesDir, "yggdrasil.conf")
        if (!file.exists()) {
            val conf = Mobile.generateConfigJSON()
            if (file.createNewFile()) {
                file.writeBytes(conf)
            }
        }
        json = JSONObject(file.readText(Charsets.UTF_8))
        // БАГ 7 ИСПРАВЛЕН: fix() теперь вызывается только один раз при создании, а не при каждом getJSON()
        fix()
    }

    fun resetJSON() {
        val conf = Mobile.generateConfigJSON()
        file.writeBytes(conf)
        json = JSONObject(file.readText(Charsets.UTF_8))
        fix()
    }

    fun resetKeys() {
        val newJson = JSONObject(String(Mobile.generateConfigJSON()))
        updateJSON { json ->
            json.put("PrivateKey", newJson.getString("PrivateKey"))
        }
    }

    fun setKeys(privateKey: String) {
        updateJSON { json ->
            json.put("PrivateKey", privateKey)
        }
    }

    @Synchronized
    fun updateJSON(fn: (JSONObject) -> Unit) {
        json = JSONObject(file.readText(Charsets.UTF_8))
        fn(json)
        val str = json.toString()
        file.writeText(str, Charsets.UTF_8)
    }

    private fun fix() {
        updateJSON { json ->
            json.put("AdminListen", "none")
            json.put("IfName", "none")
            json.put("IfMTU", 65535)

            // Multicast config
            val multicastInterfaces = json.optJSONArray("MulticastInterfaces")
            if (multicastInterfaces == null || multicastInterfaces.length() == 0 || multicastInterfaces.get(0) is String) {
                val ar = JSONArray()
                ar.put(0, JSONObject("""
                    {
                        "Regex": ".*",
                        "Beacon": true,
                        "Listen": true,
                        "Password": ""
                    }
                """.trimIndent()))
                json.put("MulticastInterfaces", ar)
            }

            // Seed once from the embedded, offline public-peer snapshot. On
            // later starts the tested/pruned list is left untouched.
            val peers = json.optJSONArray("Peers")
            val configured = buildSet {
                if (peers != null) {
                    for (index in 0 until peers.length()) {
                        peers.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            val candidates = if (preferences.getBoolean(PREF_POOL_SEEDED, false)) {
                emptyList()
            } else {
                publicPeerSnapshot()
            }
            val missingCandidates = candidates.filterNot(configured::contains)
            if (missingCandidates.isNotEmpty() || peers == null) {
                val merged = JSONArray()
                configured.forEach(merged::put)
                missingCandidates.forEach(merged::put)
                json.put("Peers", merged)
            }
            if (!preferences.getBoolean(PREF_POOL_SEEDED, false)) {
                preferences.edit().putBoolean(PREF_POOL_SEEDED, true).apply()
            }
        }
    }

    /** Persist the lowest-cost live public links after the one-time probe. */
    fun retainBestLivePeers(peersJson: String): Boolean {
        if (preferences.getBoolean(PREF_POOL_PRUNED, false)) return false
        return try {
            val live = JSONArray(peersJson)
                .let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }
                .filter { peer -> peer.optBoolean("Up", false) && peer.optString("URI").isNotBlank() }
                .sortedWith(compareBy<JSONObject> { it.optLong("Cost", Long.MAX_VALUE) }
                    .thenBy { it.optLong("Latency", Long.MAX_VALUE) })
                .map { it.getString("URI") }
                .distinct()
                .take(MAX_RETAINED_PUBLIC_PEERS)
            if (live.isEmpty()) return false
            updateJSON { it.put("Peers", JSONArray(live)) }
            preferences.edit().putBoolean(PREF_POOL_PRUNED, true).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun publicPeerSnapshot(): List<String> = try {
        val compressed = android.util.Base64.decode(PUBLIC_PEER_SNAPSHOT_GZIP_BASE64, android.util.Base64.DEFAULT)
        GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map(String::trim).filter(String::isNotEmpty)
        }
    } catch (_: Exception) {
        DEFAULT_PUBLIC_PEERS
    }

    // БАГ 7 ИСПРАВЛЕН: getJSON() больше НЕ вызывает fix() каждый раз → нет лишних записей на диск
    fun getJSON(): JSONObject = json

    fun getJSONByteArray(): ByteArray = json.toString().toByteArray(Charsets.UTF_8)

    var multicastListen: Boolean
        get() = (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).getBoolean("Listen")
        set(value) {
            updateJSON { json ->
                (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).put("Listen", value)
            }
        }

    var multicastBeacon: Boolean
        get() = (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).getBoolean("Beacon")
        set(value) {
            updateJSON { json ->
                (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).put("Beacon", value)
            }
        }

    var multicastPassword: String
        get() = (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).optString("Password")
        set(value) {
            updateJSON { json ->
                (json.getJSONArray("MulticastInterfaces").get(0) as JSONObject).put("Password", value)
            }
        }
}
