package com.example.twopchat.tor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TransportEvent {
    data class TorUnavailable(val peerName: String, val reason: String) : TransportEvent()
    data class TransportDowngradeBlocked(val peerName: String, val attemptedTransport: String) : TransportEvent()
    data class PolicyConflict(val peerName: String, val contactPolicy: String, val globalPolicy: String) : TransportEvent()
}

object TransportEventManager {
    private val _lastEvent = MutableStateFlow<TransportEvent?>(null)
    val lastEvent: StateFlow<TransportEvent?> = _lastEvent.asStateFlow()

    fun emit(event: TransportEvent) {
        _lastEvent.value = event
    }

    fun clear() {
        _lastEvent.value = null
    }
}
