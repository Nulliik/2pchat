package com.example.twopchat.ui.main

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.config.TrackerPreferences
import com.example.twopchat.NativeBridge
import com.example.twopchat.relay.P2PMessageRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatsViewModel(
    context: Context,
    private val sharedPrefs: SharedPreferences,
) : ViewModel() {
    private val appContext = context.applicationContext
    val activeChatsSet = mutableStateOf(
        sharedPrefs.getStringSet("active_chats", emptySet()).orEmpty()
            .filter { it.isNotBlank() && it != "null" && it != "Saved Messages" }.toSet()
    )
    val chatListRevision = mutableIntStateOf(0)
    val profilePhotoUri = mutableStateOf(
        sharedPrefs.getString("profile_photo_uri", null)
    )
    val currentUsername = mutableStateOf(
        sharedPrefs.getString("username_profile", "Anonymous") ?: "Anonymous",
    )
    val heroActivePeers = mutableIntStateOf(0)
    val heroUpnpOk = mutableStateOf<Boolean?>(null)
    val heroTrackersOk = mutableStateOf<Boolean?>(null)
    val heroTrackerSuccesses = mutableIntStateOf(0)
    val heroYggOk = mutableStateOf<Boolean?>(null)
    val isRefreshingAll = mutableStateOf(false)

    private fun prefetchTopActiveChats(peers: Set<String>) {
        if (peers.isEmpty()) return
        val db = com.example.twopchat.data.ChatDatabaseHelper.getInstance(appContext)
        val topChats = peers.take(5)
        for (peer in topChats) {
            if (com.example.twopchat.ui.chat.state.ChatHistoryCache.get(peer) == null) {
                try {
                    val messages = db.getMessagesForPeerPaged(peerName = peer, limit = 40, offset = 0)
                    if (messages.isNotEmpty()) {
                        com.example.twopchat.ui.chat.state.ChatHistoryCache.put(peer, messages)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private var revisionDebounceJob: kotlinx.coroutines.Job? = null

    private fun notifyChatListChanged(immediate: Boolean = false) {
        if (immediate) {
            revisionDebounceJob?.cancel()
            chatListRevision.intValue++
            return
        }
        if (revisionDebounceJob?.isActive == true) return
        revisionDebounceJob = viewModelScope.launch(Dispatchers.Main) {
            delay(150L)
            chatListRevision.intValue++
        }
    }

    private fun refreshActiveChats() {
        com.example.twopchat.relay.P2PMessageRelay.sanitizeAndMergeDanglingChats(appContext)
        val prefChats = sharedPrefs.getStringSet("active_chats", emptySet()).orEmpty()
        val db = com.example.twopchat.data.ChatDatabaseHelper.getInstance(appContext)
        val dbChats = try {
            db.getAllChatPeerNames()
        } catch (_: Exception) {
            emptySet()
        }
        val combined = (prefChats + dbChats).filter { it.isNotBlank() && it != "null" && it != "Saved Messages" }.toSet()
        if (activeChatsSet.value != combined) {
            activeChatsSet.value = combined
            notifyChatListChanged(immediate = false)
        }
        prefetchTopActiveChats(combined)
    }

    private var refreshChatsJob: kotlinx.coroutines.Job? = null

    private fun scheduleRefreshActiveChats() {
        if (refreshChatsJob?.isActive == true) return
        refreshChatsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(100L)
            refreshActiveChats()
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when {
            key == "active_chats" -> {
                scheduleRefreshActiveChats()
            }
            key?.startsWith("last_msg_") == true -> {
                val peerName = key.removePrefix("last_msg_")
                val raw = prefs.getString(key, null)
                val dec = com.example.twopchat.security.SecureStorage.decrypt(raw)
                if (dec != null) {
                    com.example.twopchat.config.P2PPreferences.lastMessageCache[peerName] = dec
                } else {
                    com.example.twopchat.config.P2PPreferences.lastMessageCache.remove(peerName)
                }
                notifyChatListChanged()
            }
            key?.startsWith("draft_msg_") == true ||
                key?.startsWith("transport_") == true ||
                key?.startsWith("last_endpoint_") == true ||
                key?.startsWith("unread_count_") == true ||
                key?.startsWith("verified_peer_") == true ||
                key?.startsWith("fingerprint_mismatch_") == true ||
                key?.startsWith("pinned_chat_") == true -> notifyChatListChanged()
            key == "profile_photo_uri" -> {
                profilePhotoUri.value = prefs.getString(key, null)
            }
            key == "username_profile" -> {
                currentUsername.value = prefs.getString(key, "Anonymous") ?: "Anonymous"
            }
        }
    }

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        viewModelScope.launch(Dispatchers.IO) {
            refreshActiveChats()
        }
        viewModelScope.launch {
            while (isActive) {
                if (NativeBridge.isLoaded) {
                    val snapshot = runCatching {
                        withContext(Dispatchers.IO) {
                            val trackerSuccesses = TrackerPreferences.activeTrackerSuccessCount(appContext)
                            val yggState = sharedPrefs.getString("yggdrasil_runtime_state", "").orEmpty()
                            val yggAddress = P2PMessageRelay.getYggdrasilAddress()
                            val isYggStateOk = yggState.equals("ENABLED", ignoreCase = true) ||
                                yggState.equals("CONNECTED", ignoreCase = true)
                            HeroSnapshot(
                                activePeers = P2PMessageRelay.getActivePeerNames().size,
                                upnpOk = true,
                                trackersOk = trackerSuccesses > 0,
                                trackerSuccesses = trackerSuccesses,
                                yggOk = isYggStateOk && yggAddress.isNotBlank(),
                            )
                        }
                    }.getOrNull()
                    snapshot?.let {
                        heroActivePeers.intValue = it.activePeers
                        heroUpnpOk.value = it.upnpOk
                        heroTrackersOk.value = it.trackersOk
                        heroTrackerSuccesses.intValue = it.trackerSuccesses
                        heroYggOk.value = it.yggOk
                    }
                }
                delay(15_000)
            }
        }
    }

    override fun onCleared() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private data class HeroSnapshot(
        val activePeers: Int,
        val upnpOk: Boolean,
        val trackersOk: Boolean,
        val trackerSuccesses: Int,
        val yggOk: Boolean,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            val preferences = P2PPreferences.prefs(appContext)
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ChatsViewModel::class.java))
                    return ChatsViewModel(appContext, preferences) as T
                }
            }
        }
    }
}
