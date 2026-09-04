package com.example.twopchat.debug

import android.content.Context
import android.content.Intent
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.runtime.GroupChatCoordinator as Groups
import com.example.twopchat.relay.P2PMessageRelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Test-only commands additionally restricted to the isolated QA application ID. */
internal object GroupE2EControl {
    fun execute(context: Context, intent: Intent, result: JSONObject) = runBlocking {
        check(context.packageName == "com.example.twopchat.groupqa")
        fun arg(name: String) = intent.getStringExtra(name).orEmpty()
        val group = arg("group")
        Groups.initialize(context)
        when (arg("op")) {
            "setup" -> {
                check(NativeBridge.initialize())
                check(NativeBridge.setNickname(arg("name")))
                P2PPreferences.prefs(context).edit().putString("username_profile", arg("name"))
                    .putInt(P2PPreferences.LISTENER_PORT, 51001).commit()
                P2PMessageRelay.startServer(context)
                result.put("fingerprint", NativeBridge.getLocalIdentity()?.fingerprint)
                result.put("port", P2PMessageRelay.listenerPort(context))
            }
            "connect" -> {
                P2PPreferences.prefs(context).edit()
                    .putString(P2PPreferences.peerFingerprint(arg("name")), arg("fingerprint")).commit()
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
                val file = java.io.File(context.cacheDir, "group-e2e.bin")
                file.writeBytes(ByteArray(1_048_613) { (it % 251).toByte() })
                Groups.sendAttachment(group, android.net.Uri.fromFile(file).toString(), "application/octet-stream", "ADB attachment")
            }
            "download" -> Groups.downloadAttachment(group, arg("message"))
            "poll" -> Groups.createPoll(group, arg("text"), listOf("Yes", "No"), false)
            "vote" -> Groups.votePoll(group, arg("message"), arg("option").toInt())
            "typing" -> Groups.sendTyping(group, arg("value").toBoolean())
            "active" -> Groups.setGroupChatActive(group, arg("value").toBoolean())
            "role" -> Groups.setMemberRole(group, arg("member"), GroupRole.valueOf(arg("role")))
            "remove" -> Groups.removeMember(group, arg("member"), arg("ban").toBoolean())
            "transfer" -> Groups.transferOwnership(group, arg("member"))
            "leave" -> Groups.leaveGroup(group)
            "sync" -> result.put("flushed", Groups.runAntiEntropy())
            "status" -> {
                result.put("groups", JSONArray(Groups.summaries.value.map { it.groupId }))
                result.put("peers", JSONObject(P2PMessageRelay.peerSessionStates.toMap()))
                if (group.isNotBlank()) {
                    val chat = Groups.chatState(group).value
                    val info = Groups.infoState(group).value
                    result.put("typing", chat.typingStatus)
                    result.put("composer", chat.textComposerEnabled)
                    com.example.twopchat.group.storage.GroupDatabaseHelper(context).use { db ->
                        result.put("epoch", db.getGroup(group)?.currentEpoch)
                        result.put("roster", JSONArray(db.listMembers(group).map {
                            JSONObject().put("name", it.peerName).put("status", it.status).put("epoch", it.joinedEpoch)
                        }))
                        result.put("events", JSONArray(db.listRecentEvents(group, 20).map {
                            JSONObject().put("id", it.eventId).put("kind", it.kind).put("epoch", it.epoch)
                        }))
                    }
                    result.put("members", JSONArray(info.members.map {
                        JSONObject().put("id", it.memberId).put("name", it.displayName).put("role", it.role.name)
                    }))
                    result.put("messages", JSONArray(chat.messages.map {
                        JSONObject().put("id", it.messageId).put("text", it.text).put("edited", it.isEdited)
                            .put("pinned", it.isPinned).put("reply", it.replyTo?.messageId)
                            .put("read", JSONArray(it.readByMembers)).put("votes", it.poll?.totalVotes)
                            .put("downloaded", it.attachment?.isDownloaded)
                            .put("path", it.attachment?.localPath)
                            .put("reactions", JSONArray(it.reactions.map { r -> JSONObject().put("emoji", r.emoji).put("count", r.count) }))
                    }))
                }
            }
            else -> error("unknown group operation")
        }
    }
}
