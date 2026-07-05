package com.example.twopchat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Onboarding : NavKey

@Serializable data object Main : NavKey

@Serializable data class Chat(val peerName: String) : NavKey
