package com.example.twopchat.protocol

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ProtocolCompatibilityNotice(fingerprint: String, language: String, group: Boolean = false) {
    val sessions by ProtocolVersionManager.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(fingerprint) { ProtocolVersionManager.refresh(fingerprint) }
    val session = sessions[fingerprint] ?: return
    val legacy = session.peerIsLegacy
    val missingSuccession = group && !session.supports(Capability.GROUP_SUCCESSION_V1)
    if (!legacy && !session.peerIsOutdated && !missingSuccession) return
    val text = when {
        missingSuccession -> when (language) {
            "English" -> "This member does not support automatic ownership succession."
            "Deutsch" -> "Dieses Mitglied unterstützt keine automatische Eigentümernachfolge."
            else -> "Участник не поддерживает автоматическую передачу прав владельца."
        }
        legacy -> when (language) {
            "English" -> "Basic chat is available. Ask your contact to update 2PChat for group features."
            "Deutsch" -> "Der Chat ist verfügbar. Für Gruppenfunktionen muss Ihr Kontakt 2PChat aktualisieren."
            else -> "Личный чат доступен. Для групповых функций попросите собеседника обновить 2PChat."
        }
        else -> when (language) {
            "English" -> "Your contact uses an older protocol. Some features may be unavailable."
            "Deutsch" -> "Ihr Kontakt verwendet ein älteres Protokoll. Einige Funktionen fehlen möglicherweise."
            else -> "У собеседника старая версия протокола. Часть функций может быть недоступна."
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
