package com.example.twopchat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.tor.TorManager
import com.example.twopchat.yggdrasil.YggdrasilCoordinator
import org.json.JSONObject

/**
 * Local-emulator control plane. It is compiled only into the debug APK and is
 * deliberately absent from release builds. Every command emits a single JSON
 * line, allowing the host test to assert facts instead of scraping UI text.
 */
class E2EControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            val result = JSONObject().put("action", intent.action.orEmpty())
            try {
                when (intent.action) {
                    "com.example.twopchat.debug.GROUP" -> GroupE2EControl.execute(context, intent, result)
                    ACTION_PROVISION -> {
                        val nickname = intent.getStringExtra(EXTRA_NICKNAME)?.trim().orEmpty()
                        require(nickname.isNotBlank()) { "nickname is required" }
                        check(NativeBridge.initialize()) { "native core initialization failed" }
                        check(NativeBridge.setNickname(nickname)) { "could not set nickname" }
                        check(NativeBridge.startListener(PORT)) { "could not start listener" }
                        val identity = NativeBridge.getLocalIdentity()
                            ?: error("native identity is unavailable")
                        result.put("nickname", nickname)
                        result.put("fingerprint", identity.fingerprint)
                        result.put("port", PORT)
                    }
                    ACTION_CONNECT -> {
                        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
                        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()
                        result.put("accepted", NativeBridge.connectPeer(endpoint, fingerprint))
                        result.put("endpoint", endpoint)
                    }
                    ACTION_SEND -> {
                        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()
                        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                        result.put("message_id", NativeBridge.sendMessage(fingerprint, body).orEmpty())
                    }
                    ACTION_STATUS -> {
                        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()
                        require(fingerprint.isNotBlank()) { "fingerprint is required" }
                        result.put("online", NativeBridge.isPeerOnline(fingerprint))
                    }
                    ACTION_PEER_STATE -> {
                        val name = intent.getStringExtra(EXTRA_NICKNAME).orEmpty()
                        require(name.isNotBlank()) { "peer name is required" }
                        val fingerprint = P2PPreferences.getPeerFingerprint(context, name).orEmpty()
                        result.put("ygg_enabled", P2PPreferences.prefs(context).getBoolean("settings_yggdrasil", false))
                        result.put("wifi_discovery", P2PPreferences.isWifiDiscoveryEnabled(context))
                        result.put("online", fingerprint.isNotBlank() && NativeBridge.isPeerOnline(fingerprint))
                        result.put("transport", P2PMessageRelay.peerConnectionTransports[name].orEmpty())
                        result.put("messages", org.json.JSONArray(
                            com.example.twopchat.data.ChatDatabaseHelper.getInstance(context)
                                .getMessagesForPeer(name).map { it.text }
                        ))
                    }
                    ACTION_APP_SEND -> {
                        val name = intent.getStringExtra(EXTRA_NICKNAME).orEmpty()
                        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                        require(name.isNotBlank() && body.isNotBlank())
                        val completion = java.util.concurrent.CompletableFuture<Boolean>()
                        P2PMessageRelay.sendMessageToPeer(context, name, body) { completion.complete(it) }
                        // A concurrent large-file transfer can hold the peer send lock for
                        // minutes; the debug harness must wait, not time out.
                        result.put("accepted", completion.get(120, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    ACTION_APP_FILE -> {
                        val name = intent.getStringExtra(EXTRA_NICKNAME).orEmpty()
                        val filename = intent.getStringExtra("filename").orEmpty()
                        val allowed = setOf("test_image.jpg", "test_video.mp4", "test_big.dat", "test_huge.dat")
                        require(name.isNotBlank() && filename in allowed)
                        val file = java.io.File(context.filesDir, filename)
                        require(file.isFile) { "test media is missing" }
                        val endpoint = P2PPreferences.getEffectiveEndpointsForPeer(context, name)
                        val yggEndpoint = P2PPreferences.filterEndpointsByPreference(
                            endpoint.split(','), P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY
                        ).firstOrNull() ?: error("no Yggdrasil endpoint for contact")
                        P2PMessageRelay.sendFile(context, name, yggEndpoint, file.absolutePath,
                            caption = "ADB Yggdrasil $filename") { completed ->
                            Log.i(TAG, JSONObject()
                                .put("action", "APP_FILE_RESULT")
                                .put("filename", filename)
                                .put("completed", completed)
                                .toString())
                        }
                        result.put("requested", true)
                    }
                    ACTION_RETRY_YGG -> {
                        YggdrasilCoordinator.connect(context)
                        result.put("requested", true)
                    }
                    ACTION_REAPPLY_POLICY -> {
                        result.put("applied", com.example.twopchat.config.ProxyConfig.updateNetworkProxy(context))
                        result.put("native_applied", NativeBridge.applyPolicy(12))
                        result.put("ygg_enabled", P2PPreferences.prefs(context).getBoolean("settings_yggdrasil", false))
                    }
                    ACTION_INVITE -> {
                        val name = P2PPreferences.username(context)
                        require(name.isNotBlank()) { "profile name is unavailable" }
                        result.put("name", name)
                        result.put("code", P2PPreferences.getRendezvousCode(context))
                        result.put("fingerprint", NativeBridge.getLocalIdentity()?.fingerprint.orEmpty())
                        // Read-only diagnostic metadata; this receiver is absent from release builds.
                        result.put("ygg", com.example.twopchat.relay.P2PMessageRelay.getYggdrasilAddress())
                    }
                    ACTION_TRACKER -> {
                        val tracker = intent.getStringExtra(EXTRA_TRACKER).orEmpty()
                        val hash = intent.getStringExtra(EXTRA_INFO_HASH).orEmpty()
                        require(tracker.isNotBlank() && hash.isNotBlank()) { "tracker and info hash are required" }
                        check(NativeBridge.updateTrackers(listOf(tracker))) { "could not update tracker" }
                        result.put("discovery_started", NativeBridge.startDiscovery(listOf(tracker), listOf(hash), PORT))
                        result.put("announced", NativeBridge.announceSelf(hash, PORT))
                        result.put("tracker", tracker)
                        result.put("info_hash", hash)
                    }
                    ACTION_PROXY -> {
                        P2PPreferences.prefs(context).edit().putBoolean("settings_yggdrasil", true).commit()
                        P2PPreferences.setYggdrasilMode(context, P2PPreferences.YggdrasilMode.PROXY)
                        YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.PROXY)
                        result.put("mode", "proxy")
                    }
                    ACTION_VPN -> {
                        P2PPreferences.setYggdrasilMode(context, P2PPreferences.YggdrasilMode.VPN)
                        val consentIntent = android.net.VpnService.prepare(context)
                        val consentRequired = consentIntent != null
                        result.put("consent_required", consentRequired)
                        if (consentRequired) {
                            consentIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(consentIntent)
                            result.put("consent_started", true)
                        } else {
                            YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.VPN)
                        }
                    }
                    ACTION_TOR -> {
                        TorManager.startTor(context)
                        result.put("requested", true)
                    }
                    else -> error("unsupported action")
                }
                result.put("ok", true)
            } catch (t: Throwable) {
                result.put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
            } finally {
                Log.i(TAG, result.toString())
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val TAG = "2PChatE2E"
        const val ACTION_PROVISION = "com.example.twopchat.debug.PROVISION"
        const val ACTION_CONNECT = "com.example.twopchat.debug.CONNECT"
        const val ACTION_SEND = "com.example.twopchat.debug.SEND"
        const val ACTION_STATUS = "com.example.twopchat.debug.STATUS"
        const val ACTION_PEER_STATE = "com.example.twopchat.debug.PEER_STATE"
        const val ACTION_APP_SEND = "com.example.twopchat.debug.APP_SEND"
        const val ACTION_APP_FILE = "com.example.twopchat.debug.APP_FILE"
        const val ACTION_RETRY_YGG = "com.example.twopchat.debug.RETRY_YGG"
        const val ACTION_REAPPLY_POLICY = "com.example.twopchat.debug.REAPPLY_POLICY"
        const val ACTION_INVITE = "com.example.twopchat.debug.INVITE"
        const val ACTION_TRACKER = "com.example.twopchat.debug.TRACKER"
        const val ACTION_PROXY = "com.example.twopchat.debug.PROXY"
        const val ACTION_VPN = "com.example.twopchat.debug.VPN"
        const val ACTION_TOR = "com.example.twopchat.debug.TOR"
        const val EXTRA_NICKNAME = "nickname"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_BODY = "body"
        const val EXTRA_TRACKER = "tracker"
        const val EXTRA_INFO_HASH = "info_hash"
        const val PORT = 50001
    }
}
