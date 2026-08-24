package com.example.twopchat.ui.main

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.NativeBridge
import com.example.twopchat.relay.P2PMessageRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatsViewModel(
    private val sharedPrefs: SharedPreferences,
) : ViewModel() {
    val activeChatsSet = mutableStateOf(
        sharedPrefs.getStringSet("active_chats", emptySet()).orEmpty().toSet(),
    )
    val chatListRevision = mutableIntStateOf(0)
    val profilePhotoUri = mutableStateOf(sharedPrefs.getString("profile_photo_uri", null))
    val currentUsername = mutableStateOf(
        sharedPrefs.getString("username_profile", "Anonymous") ?: "Anonymous",
    )
    val heroActivePeers = mutableIntStateOf(0)
    val heroUpnpOk = mutableStateOf<Boolean?>(null)
    val heroTrackersOk = mutableStateOf<Boolean?>(null)
    val heroYggOk = mutableStateOf<Boolean?>(null)
    val isRefreshingAll = mutableStateOf(false)

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when {
            key == "active_chats" -> {
                activeChatsSet.value = prefs.getStringSet("active_chats", emptySet()).orEmpty().toSet()
                chatListRevision.intValue++
            }
            key?.startsWith("last_msg_") == true ||
                key?.startsWith("draft_msg_") == true ||
                key?.startsWith("transport_") == true ||
                key?.startsWith("last_endpoint_") == true ||
                key?.startsWith("unread_count_") == true -> chatListRevision.intValue++
            key == "profile_photo_uri" -> profilePhotoUri.value = prefs.getString(key, null)
            key == "username_profile" -> {
                currentUsername.value = prefs.getString(key, "Anonymous") ?: "Anonymous"
            }
        }
    }

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        viewModelScope.launch {
            while (isActive) {
                if (NativeBridge.isLoaded) {
                    val snapshot = runCatching {
                        withContext(Dispatchers.IO) {
                            HeroSnapshot(
                                activePeers = P2PMessageRelay.getActivePeerNames().size,
                                upnpOk = true,
                                trackersOk = true,
                                // Both VPN and user-space Proxy publish their
                                // address/state here. The relay owns neither
                                // service in Proxy mode, so querying only the
                                // relay made a live SOCKS Yggdrasil stack look
                                // falsely disabled in the dashboard.
                                yggOk = sharedPrefs.getString("yggdrasil_runtime_state", "")
                                    .equals("ENABLED", ignoreCase = true) ||
                                    sharedPrefs.getString("yggdrasil_runtime_state", "")
                                        .equals("CONNECTED", ignoreCase = true),
                            )
                        }
                    }.getOrNull()
                    snapshot?.let {
                        heroActivePeers.intValue = it.activePeers
                        heroUpnpOk.value = it.upnpOk
                        heroTrackersOk.value = it.trackersOk
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
        val yggOk: Boolean,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val preferences = P2PPreferences.prefs(context.applicationContext)
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ChatsViewModel::class.java))
                    return ChatsViewModel(preferences) as T
                }
            }
        }
    }
}
