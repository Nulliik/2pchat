package com.example.twopchat.ui.main

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.BuiltInTracker
import com.example.twopchat.CustomTracker
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.PythonBridge
import com.example.twopchat.TrackerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun TrackerSettingsPage(
    appLanguage: String,
    onBackClick: () -> Unit,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    val isRussian = appLanguage == "Русский"

    val enabledProtocols = remember(revision) { TrackerPreferences.enabledProtocols(context) }
    val disabledBuiltIns = remember(revision) { TrackerPreferences.disabledBuiltIns(context) }
    val customTrackers = remember(revision) { TrackerPreferences.customTrackers(context) }
    val announceEnabled = remember(revision) { TrackerPreferences.announceEnabled(context) }
    val dhtEnabled = remember(revision) { TrackerPreferences.dhtEnabled(context) }

    fun settingsChanged() {
        revision += 1
        scope.launch(Dispatchers.IO) {
            PythonBridge.applyTrackerConfiguration()
            P2PMessageRelay.refreshAnnouncement(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageLayout(
            title = if (isRussian) "Трекеры и обнаружение" else "Trackers & Discovery",
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
        ) {
            TrackerSectionCard(surfaceColor, onSurfaceColor) {
                TrackerToggleRow(
                    title = if (isRussian) "Публиковать мой адрес" else "Announce my address",
                    subtitle = if (isRussian) {
                        "Разрешает announce на выбранных трекерах и DHT. Уже опубликованные записи исчезнут после истечения срока."
                    } else {
                        "Allows announces to selected trackers and DHT. Existing records disappear after they expire."
                    },
                    checked = announceEnabled,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = {
                        TrackerPreferences.setAnnounceEnabled(context, it)
                        settingsChanged()
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TrackerToggleRow(
                    title = "Mainline DHT (BEP 5)",
                    subtitle = if (isRussian) "Децентрализованное обнаружение без трекера" else "Decentralized discovery without a tracker",
                    checked = dhtEnabled,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = {
                        TrackerPreferences.setDhtEnabled(context, it)
                        settingsChanged()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isRussian) "Типы трекеров" else "Tracker types",
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TrackerSectionCard(surfaceColor, onSurfaceColor) {
                listOf("https", "udp", "http").forEachIndexed { index, protocol ->
                    if (index > 0) HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                    TrackerToggleRow(
                        title = protocol.uppercase(),
                        subtitle = when (protocol) {
                            "http" -> if (isRussian) {
                                "Без шифрования: оператор сети может видеть и изменять запросы"
                            } else {
                                "Unencrypted: the network operator can observe or modify requests"
                            }
                            "https" -> if (isRussian) "HTTP с TLS-шифрованием" else "HTTP protected with TLS"
                            else -> if (isRussian) "Компактный UDP-протокол BitTorrent" else "Compact BitTorrent UDP protocol"
                        },
                        checked = protocol in enabledProtocols,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = if (protocol == "http") Color(0xFFE67E22) else onSurfaceVariant,
                        onCheckedChange = {
                            TrackerPreferences.setProtocolEnabled(context, protocol, it)
                            settingsChanged()
                        },
                    )
                }
            }

            TrackerPreferences.builtInTrackers.groupBy { it.protocol }.forEach { (protocol, trackers) ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (isRussian) "Встроенные ${protocol.uppercase()}" else "Built-in ${protocol.uppercase()}",
                    color = onSurfaceColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                TrackerSectionCard(surfaceColor, onSurfaceColor) {
                    trackers.forEachIndexed { index, tracker ->
                        if (index > 0) HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                        BuiltInTrackerRow(
                            tracker = tracker,
                            protocolEnabled = protocol in enabledProtocols,
                            enabled = tracker.name !in disabledBuiltIns,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceVariant = onSurfaceVariant,
                            onEnabledChange = {
                                TrackerPreferences.setBuiltInEnabled(context, tracker.name, it)
                                settingsChanged()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isRussian) "Мои трекеры" else "My trackers",
                    color = onSurfaceColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                TextButton(onClick = { showAddDialog = true }) {
                    Text(if (isRussian) "Добавить трекер" else "Add tracker", color = primaryColor)
                }
            }
            if (customTrackers.isEmpty()) {
                Text(
                    text = if (isRussian) "Пользовательские трекеры не добавлены" else "No custom trackers added",
                    color = onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                TrackerSectionCard(surfaceColor, onSurfaceColor) {
                    customTrackers.forEachIndexed { index, tracker ->
                        if (index > 0) HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                        CustomTrackerRow(
                            tracker = tracker,
                            protocolEnabled = tracker.protocol in enabledProtocols,
                            appLanguage = appLanguage,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceVariant = onSurfaceVariant,
                            onEnabledChange = {
                                TrackerPreferences.setCustomTrackerEnabled(context, tracker.id, it)
                                settingsChanged()
                            },
                            onDelete = {
                                TrackerPreferences.deleteCustomTracker(context, tracker.id)
                                settingsChanged()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    TrackerPreferences.resetDefaults(context)
                    settingsChanged()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isRussian) "Вернуть значения по умолчанию" else "Restore defaults")
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showAddDialog) {
        AddTrackerDialog(
            appLanguage = appLanguage,
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                val error = TrackerPreferences.addCustomTracker(context, name, url)
                if (error == null) {
                    showAddDialog = false
                    settingsChanged()
                }
                error
            },
        )
    }
}

@Composable
private fun TrackerSectionCard(
    surfaceColor: Color,
    onSurfaceColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp)),
    ) {
        Column(content = content)
    }
}

@Composable
private fun TrackerToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = onSurfaceColor.copy(alpha = if (enabled) 1f else 0.45f), fontWeight = FontWeight.Medium)
            Text(subtitle, color = onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.55f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BuiltInTrackerRow(
    tracker: BuiltInTracker,
    protocolEnabled: Boolean,
    enabled: Boolean,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onEnabledChange: (Boolean) -> Unit,
) {
    TrackerToggleRow(
        title = tracker.name,
        subtitle = tracker.url,
        checked = enabled && protocolEnabled,
        enabled = protocolEnabled,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        onCheckedChange = onEnabledChange,
    )
}

@Composable
private fun CustomTrackerRow(
    tracker: CustomTracker,
    protocolEnabled: Boolean,
    appLanguage: String,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tracker.name, color = onSurfaceColor, fontWeight = FontWeight.Medium)
                Text(tracker.url, color = onSurfaceVariant, fontSize = 12.sp)
            }
            Switch(
                checked = tracker.enabled && protocolEnabled,
                enabled = protocolEnabled,
                onCheckedChange = onEnabledChange,
            )
        }
        TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
            Text(if (appLanguage == "Русский") "Удалить" else "Delete")
        }
    }
}

@Composable
private fun AddTrackerDialog(
    appLanguage: String,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> String?,
) {
    val isRussian = appLanguage == "Русский"
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRussian) "Добавить трекер" else "Add tracker") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(if (isRussian) "Название" else "Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("URL") },
                    placeholder = { Text("https://tracker.example/announce") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onAdd(name, url) }) {
                Text(if (isRussian) "Добавить" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isRussian) "Отмена" else "Cancel") }
        },
    )
}
