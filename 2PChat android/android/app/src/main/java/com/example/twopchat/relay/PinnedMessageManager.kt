package com.example.twopchat.relay

import android.content.Context
import com.example.twopchat.P2PPreferences
import com.example.twopchat.PinnedMessageStateVersion
import com.example.twopchat.SecureStorage
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.nextPinnedMessageStateVersion
import com.example.twopchat.shouldApplyPinnedMessageState

internal class PinnedMessageManager {
    fun handlePinMessage(
        context: Context,
        sender: String,
        msgId: String,
        text: String,
        isFromSender: Boolean,
        pinVersionCounter: Long,
        pinActor: String,
        controlId: String,
        onPinned: (String, String, String, Boolean) -> Unit,
        sendAck: (Context, String, String) -> Unit
    ) {
        if (msgId.isBlank()) return
        val prefs = P2PPreferences.prefs(context)
        val currentVersion = P2PPreferences.currentPinnedStateVersion(prefs, sender)
        val advertisedVersion = PinnedMessageStateVersion(
            counter = pinVersionCounter,
            actor = pinActor,
        )
        val incomingVersion = if (
            advertisedVersion.counter > 0L && advertisedVersion.actor.isNotBlank()
        ) {
            advertisedVersion
        } else {
            nextPinnedMessageStateVersion(currentVersion, "legacy:$sender")
        }
        var stateHandled = true
        if (shouldApplyPinnedMessageState(currentVersion, incomingVersion)) {
            val storedText = ChatDatabaseHelper.getInstance(context)
                .findMessageForReaction(sender, msgId, "")
                ?.text
                ?: text
            ChatDatabaseHelper.getInstance(context).updateMessagePinned(msgId, true)
            stateHandled = prefs.edit()
                .putString(P2PPreferences.pinnedMessageId(sender), msgId)
                .putString(
                    P2PPreferences.pinnedMessageText(sender),
                    SecureStorage.encrypt(storedText),
                )
                .putString(
                    P2PPreferences.pinnedMessageSender(sender),
                    if (isFromSender) sender else "You",
                )
                .putString(P2PPreferences.pinnedBy(sender), sender)
                .putLong(
                    P2PPreferences.pinnedStateVersion(sender),
                    incomingVersion.counter,
                )
                .putString(
                    P2PPreferences.pinnedStateActor(sender),
                    incomingVersion.actor,
                )
                .commit()
            if (stateHandled) {
                onPinned(sender, msgId, storedText, isFromSender)
            }
        }
        if (stateHandled && controlId.isNotBlank()) {
            sendAck(context, sender, controlId)
        }
    }

    fun handleUnpinMessage(
        context: Context,
        sender: String,
        pinVersionCounter: Long,
        pinActor: String,
        controlId: String,
        onUnpinned: (String) -> Unit,
        sendAck: (Context, String, String) -> Unit
    ) {
        val prefs = P2PPreferences.prefs(context)
        val currentVersion = P2PPreferences.currentPinnedStateVersion(prefs, sender)
        val advertisedVersion = PinnedMessageStateVersion(
            counter = pinVersionCounter,
            actor = pinActor,
        )
        val incomingVersion = if (
            advertisedVersion.counter > 0L && advertisedVersion.actor.isNotBlank()
        ) {
            advertisedVersion
        } else {
            nextPinnedMessageStateVersion(currentVersion, "legacy:$sender")
        }
        var stateHandled = true
        if (shouldApplyPinnedMessageState(currentVersion, incomingVersion)) {
            stateHandled = prefs.edit()
                .remove(P2PPreferences.pinnedMessageId(sender))
                .remove(P2PPreferences.pinnedMessageText(sender))
                .remove(P2PPreferences.pinnedMessageSender(sender))
                .remove(P2PPreferences.pinnedBy(sender))
                .putLong(
                    P2PPreferences.pinnedStateVersion(sender),
                    incomingVersion.counter,
                )
                .putString(
                    P2PPreferences.pinnedStateActor(sender),
                    incomingVersion.actor,
                )
                .commit()
            if (stateHandled) {
                onUnpinned(sender)
            }
        }
        if (stateHandled && controlId.isNotBlank()) {
            sendAck(context, sender, controlId)
        }
    }
}
