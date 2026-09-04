package com.example.twopchat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
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
                    ACTION_INVITE -> {
                        val name = P2PPreferences.username(context)
                        require(name.isNotBlank()) { "profile name is unavailable" }
                        result.put("name", name)
                        result.put("code", P2PPreferences.getRendezvousCode(context))
                        result.put("fingerprint", NativeBridge.getLocalIdentity()?.fingerprint.orEmpty())
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
