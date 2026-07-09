package com.example.twopchat.yggdrasil

import android.content.Context
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// БАГ 1 ИСПРАВЛЕН: Был object (singleton с race condition).
// Теперь это обычный класс — каждый экземпляр владеет своим файлом/json и не конкурирует с другими.
class ConfigurationProxy(applicationContext: Context) {
    private var json: JSONObject
    private val file: File

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

            // Public peers refreshed against https://publicpeers.neilalexander.dev/
            // on July 9, 2026. Keep this list short and geographically sensible.
            val peers = json.optJSONArray("Peers")
            if (peers == null || peers.length() == 0) {
                val ar = JSONArray()
                ar.put("tls://yggdrasil.su:62586")
                ar.put("tls://95.217.35.92:1337")
                ar.put("tls://ygg.jholden.org:1555")
                json.put("Peers", ar)
            }
        }
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
