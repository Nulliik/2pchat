package com.example.twopchat.debug

import android.content.Context
import android.content.Intent
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.runtime.GroupChatCoordinator as Groups
import com.example.twopchat.group.ui.GroupMemberPermissions
import com.example.twopchat.media.AttachmentStorageManager
import com.example.twopchat.relay.P2PMessageRelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Test-only commands additionally restricted to the isolated QA application ID. */
internal object GroupE2EControl {
    fun execute(context: Context, intent: Intent, result: JSONObject) = runBlocking {
        check(context.packageName == "com.example.twopchat.groupqa")
        fun arg(name: String) = intent.getStringExtra(name).orEmpty()
        val group = arg("group")
        Groups.initialize(context)
        when (arg("op")) {
            "peer_seed" -> {
                val prefs = P2PPreferences.prefs(context)
                prefs.edit().putString(P2PPreferences.peerFingerprint(arg("name")), arg("fingerprint"))
                    .putString(P2PPreferences.lastEndpoint(arg("name")), arg("endpoints"))
                    .putStringSet("active_chats", prefs.getStringSet("active_chats", emptySet()).orEmpty() + arg("name"))
                    .commit()
            }
            "peer_status" -> {
                val name = arg("name")
                val prefs = P2PPreferences.prefs(context)
                val fp = prefs.getString(P2PPreferences.peerFingerprint(name), "").orEmpty()
                result.put("online", NativeBridge.isPeerOnline(fp))
                result.put("projection", prefs.getString(P2PPreferences.lastEndpoint(name), ""))
                result.put("routes", JSONObject(P2PMessageRelay.peerEndpoints.toMap()))
                result.put("ygg", P2PMessageRelay.getYggdrasilAddress())
                val db = com.example.twopchat.data.ChatDatabaseHelper.getInstance(context)
                val rows = JSONArray()
                db.endpointTransaction { sql ->
                    sql.rawQuery("SELECT endpoint,source,last_success,last_seen,failures FROM peer_endpoint_records WHERE fingerprint = ?", arrayOf(fp)).use {
                        while (it.moveToNext()) rows.put(JSONObject().put("endpoint", it.getString(0))
                            .put("source", it.getString(1)).put("success", it.getLong(2))
                            .put("seen", it.getLong(3)).put("failures", it.getInt(4)))
                    }
                }
                result.put("records", rows)
                result.put("messages", JSONArray(db.getMessagesForPeer(name).map {
                    JSONObject().put("id", it.id).put("text", it.text)
                }))
            }
            "peer_send" -> {
                val sent = CompletableDeferred<Boolean>()
                P2PMessageRelay.sendMessageToPeer(context, arg("name"), arg("text")) { sent.complete(it) }
                result.put("accepted", withTimeout(8_000) { sent.await() })
            }
            "peer_lookup" -> {
                val fp = P2PPreferences.getPeerFingerprint(context, arg("name"))
                com.example.twopchat.bridge.P2PBridgeProvider.get(context).searchPeers(arg("name"), arg("name"), fp, null)
            }
            "network_status" -> {
                val prefs = P2PPreferences.prefs(context)
                result.put("ygg", P2PMessageRelay.getYggdrasilAddress())
                result.put("ygg_state", prefs.getString("yggdrasil_runtime_state", ""))
                result.put("ygg_peers", prefs.getInt("yggdrasil_runtime_peers", 0))
                result.put("tor_running", com.example.twopchat.tor.TorManager.isTorRunning.value)
                result.put("tor_connecting", com.example.twopchat.tor.TorManager.isTorConnecting.value)
                result.put("onion", P2PPreferences.getTorOnionHostname(context).orEmpty())
                result.put("transports", JSONObject(P2PMessageRelay.peerConnectionTransports.toMap()))
                result.put("trackers", JSONObject(com.example.twopchat.config.TrackerPreferences.diagnosticStatuses(context)))
            }
            "peer_transport" -> {
                val name = arg("name")
                val pref = P2PPreferences.PeerTransportPreference.valueOf(arg("mode"))
                val fp = checkNotNull(P2PPreferences.getPeerFingerprint(context, name))
                P2PPreferences.setPeerTransportPreference(context, name, pref)
                val flags = when (pref) {
                    P2PPreferences.PeerTransportPreference.AUTO -> 0
                    P2PPreferences.PeerTransportPreference.DIRECT_ONLY -> 3
                    P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY -> 4
                    P2PPreferences.PeerTransportPreference.TOR_ONLY -> 8
                }
                check(NativeBridge.setPeerPolicy(fp, flags))
                val bridge = com.example.twopchat.bridge.P2PBridgeProvider.get(context)
                bridge.closePeerSession(name, fp)
                result.put("accepted", bridge.reconnectPeerSession(name,
                    P2PPreferences.getEffectiveEndpointsForPeer(context, name), fp))
            }
            "peer_tracker" -> {
                val trackers = com.example.twopchat.config.TrackerPreferences
                P2PPreferences.prefs(context).edit()
                    .putStringSet(trackers.DISABLED_BUILTINS, trackers.builtInTrackers.map { it.name }.toSet())
                    .putString(trackers.CUSTOM_TRACKERS_JSON, JSONArray().put(JSONObject()
                        .put("id", "peer-e2e").put("name", "Peer E2E").put("url", arg("url"))
                        .put("protocol", "http").put("enabled", true)).toString()).commit()
                NativeBridge.updateTrackers(listOf(arg("url")))
            }
            "setup" -> {
                val port = arg("port").toIntOrNull() ?: 51001
                require(port in 1..65535)
                check(NativeBridge.initialize())
                check(NativeBridge.setNickname(arg("name")))
                P2PPreferences.prefs(context).edit()
                    .putBoolean("onboarding_completed", true)
                    .putString("username_profile", arg("name"))
                    .putInt(P2PPreferences.LISTENER_PORT, port).commit()
                NativeBridge.stopListener()
                check(NativeBridge.startListener(port)) { "Failed to start listener on $port" }
                P2PMessageRelay.startServer(context)
                P2PMessageRelay.resetPeerBackoffs()
                val prefs = P2PPreferences.prefs(context)
                val chats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
                    .filterNot { it == "Saved Messages" }
                for (peerName in chats) {
                    val fingerprint = prefs.getString("peer_fingerprint_$peerName", "").orEmpty()
                    val liveEndpoint = P2PMessageRelay.peerEndpoints[peerName]
                    val endpoint = P2PPreferences.getEffectiveEndpointsForPeer(context, peerName, liveEndpoint)
                    if (endpoint.isNotBlank()) {
                        com.example.twopchat.bridge.P2PBridgeProvider.get(context).reconnectPeerSession(peerName, endpoint, fingerprint)
                    }
                }
                result.put("fingerprint", NativeBridge.getLocalIdentity()?.fingerprint)
                result.put("code", P2PPreferences.getRendezvousCode(context))
                result.put("port", P2PMessageRelay.listenerPort(context))
            }
            "connect" -> {
                P2PPreferences.prefs(context).edit()
                    .putString(P2PPreferences.peerFingerprint(arg("name")), arg("fingerprint")).apply()
                P2PMessageRelay.injectLocalDiscoveryCandidate(arg("name"), arg("fingerprint"), arg("endpoint"))
                result.put("accepted", NativeBridge.connectPeer(arg("endpoint"), arg("fingerprint")))
            }
            "create" -> {
                val created = CompletableDeferred<String>()
                Groups.createGroup(arg("title"), "ADB group regression", arg("contacts").split(',').filter { it.isNotBlank() }.toSet()) {
                    created.complete(it)
                }
                result.put("group", withTimeout(8_000) { created.await() })
            }
            "accept" -> result.put("accepted", Groups.acceptPendingInviteForGroup(group))
            "send" -> Groups.sendMessage(group, arg("text"), arg("reply").ifBlank { null })
            "edit" -> Groups.editMessage(group, arg("message"), arg("text"))
            "delete" -> Groups.deleteMessage(group, arg("message"))
            "react" -> Groups.toggleReaction(group, arg("message"), arg("emoji"))
            "pin" -> Groups.pinMessage(group, arg("message"))
            "unpin" -> Groups.unpinMessage(group, arg("message"))
            "attachment" -> {
                val size = arg("size").toIntOrNull() ?: 1_048_613
                val name = arg("name").ifBlank { "group-e2e.bin" }
                val mime = arg("mime").ifBlank { "application/octet-stream" }
                val caption = arg("caption").ifBlank { "ADB attachment" }
                val file = File(context.cacheDir, name)
                file.writeBytes(ByteArray(size) { (it % 251).toByte() })
                Groups.sendAttachment(group, android.net.Uri.fromFile(file).toString(), mime, caption)
            }
            "album" -> {
                val count = arg("count").toIntOrNull()?.coerceIn(2, 10) ?: 2
                val uris = mutableListOf<String>()
                val mimeTypes = mutableListOf<String>()
                for (i in 1..count) {
                    val file = File(context.cacheDir, "group-album-$i.bin")
                    file.writeBytes(ByteArray(64 * 1024) { ((it + i) % 251).toByte() })
                    uris += android.net.Uri.fromFile(file).toString()
                    mimeTypes += "application/octet-stream"
                }
                Groups.sendMediaAlbum(group, uris, mimeTypes, arg("caption").ifBlank { "ADB album" })
            }
            "download" -> Groups.downloadAttachment(group, arg("message"))
            "poll" -> Groups.createPoll(group, arg("text"), listOf("Yes", "No"), false)
            "vote" -> Groups.votePoll(group, arg("message"), arg("option").toInt())
            "typing" -> Groups.sendTyping(group, arg("value").toBoolean())
            "active" -> Groups.setGroupChatActive(group, arg("value").toBoolean())
            "role" -> Groups.setMemberRole(group, arg("member"), GroupRole.valueOf(arg("role")))
            "restrict" -> {
                val canSend = arg("can_send").ifBlank { "true" }.toBoolean()
                val canMedia = arg("can_media").ifBlank { "true" }.toBoolean()
                val canLinks = arg("can_links").ifBlank { "true" }.toBoolean()
                val canPin = arg("can_pin").ifBlank { "false" }.toBoolean()
                Groups.setMemberRestrictions(
                    groupId = group,
                    memberId = arg("member"),
                    permissions = GroupMemberPermissions(
                        canSendMessages = canSend,
                        canSendMedia = canMedia,
                        canSendLinks = canLinks,
                        canPinMessages = canPin,
                    ),
                )
            }
            "admin_only" -> Groups.setAdminOnlyPosting(group, arg("value").toBoolean())
            "update_info" -> Groups.updateGroupInfo(group, arg("title"), arg("description"))
            "wallpaper" -> Groups.updateGroupWallpaper(group, arg("uri").ifBlank { null })
            "remove" -> Groups.removeMember(group, arg("member"), arg("ban").toBoolean())
            "transfer" -> Groups.transferOwnership(group, arg("member"))
            "leave" -> Groups.leaveGroup(group)
            "sync" -> result.put("flushed", Groups.runAntiEntropy())
            "cache_maintenance" -> {
                val cleanResult = AttachmentStorageManager.runCacheMaintenance(context, force = true)
                result.put("deleted_bytes", cleanResult.deletedBytes)
                result.put("deleted_files", cleanResult.deletedFiles)
                result.put("detached_messages", cleanResult.detachedMessages)
                result.put("cached_bytes", P2PPreferences.getCachedMediaBytes(context))
            }
            "status" -> {
                result.put("groups", JSONArray(Groups.summaries.value.map { it.groupId }))
                result.put("peers", JSONObject(P2PMessageRelay.peerSessionStates.toMap()))
                result.put("cached_bytes", P2PPreferences.getCachedMediaBytes(context))
                if (group.isNotBlank()) {
                    val chat = Groups.chatState(group).value
                    val info = Groups.infoState(group).value
                    result.put("title", chat.title)
                    result.put("typing", chat.typingStatus)
                    result.put("composer", chat.textComposerEnabled)
                    result.put("text_composer", chat.textComposerEnabled)
                    result.put("media_composer", chat.mediaComposerEnabled)
                    result.put("read_only_reason", chat.readOnlyReason)
                    result.put("admin_only", info.metadata.adminOnlyPosting)
                    result.put("my_role", info.currentUserRole.name)
                    result.put("wallpaper", chat.wallpaperUri)
                    com.example.twopchat.group.storage.GroupDatabaseHelper(context).use { db ->
                        result.put("epoch", db.getGroup(group)?.currentEpoch)
                    }
                    result.put("members", JSONArray(info.members.map {
                        val roleName = if (it.role.name == "ADMIN") "ADMINISTRATOR" else it.role.name
                        JSONObject().put("id", it.memberId).put("name", it.displayName).put("role", roleName)
                            .put("can_send", it.permissions.canSendMessages)
                            .put("can_media", it.permissions.canSendMedia)
                    }))
                    result.put("messages", JSONArray(chat.messages.map {
                        val m = JSONObject().put("id", it.messageId).put("text", it.text)
                            .put("edited", it.isEdited)
                            .put("pinned", it.isPinned)
                            .put("read", JSONArray(it.readByMembers))
                            .put("reactions", JSONArray(it.reactions.map { r -> JSONObject().put("emoji", r.emoji).put("count", r.count) }))
                        it.replyTo?.messageId?.let { r -> m.put("reply", r) }
                        it.poll?.totalVotes?.let { v -> m.put("votes", v) }
                        it.attachment?.isDownloaded?.let { d -> m.put("downloaded", d) }
                        it.attachment?.localPath?.let { p -> m.put("path", p) }
                        m
                    }))
                }
            }
            else -> error("unknown group operation")
        }
    }
}
