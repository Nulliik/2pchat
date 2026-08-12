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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.collectAsState
import com.example.twopchat.BuiltInTracker
import com.example.twopchat.CustomTracker
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences
import com.example.twopchat.ProxyConfig
import com.example.twopchat.PythonBridge
import com.example.twopchat.TorManager
import com.example.twopchat.TorStatusFormatter
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
    val clearnetEnabled = remember(revision) { TrackerPreferences.clearnetTrackersEnabled(context) }
    val yggEnabled = remember(revision) { TrackerPreferences.yggTrackersEnabled(context) }
    val ipv4Mode = remember(revision) { TrackerPreferences.ipv4AnnounceMode(context) }
    val proxyEnabled = remember(revision) { P2PPreferences.isProxyEnabled(context) }
    val proxyHost = remember(revision) { P2PPreferences.getProxyHost(context) }
    val proxyPortText = remember(revision) { P2PPreferences.getProxyPort(context).toString() }
    val isTorRunning by TorManager.isTorRunning.collectAsState()
    var torUserRequested by remember { mutableStateOf(proxyEnabled || isTorRunning) }

    fun settingsChanged() {
        revision += 1
        scope.launch(Dispatchers.IO) {
            PythonBridge.applyTrackerConfiguration()
            PythonBridge.applyProxyConfiguration()
            P2PMessageRelay.refreshAnnouncement(context)
        }
    }

    LaunchedEffect(isTorRunning) {
        if (isTorRunning) {
            P2PPreferences.prefs(context).edit()
                .putBoolean(P2PPreferences.PROXY_ENABLED, true)
                .putString(P2PPreferences.PROXY_HOST, "127.0.0.1")
                .putInt(P2PPreferences.PROXY_PORT, 9050)
                .apply()
            settingsChanged()
        } else if (!torUserRequested && proxyEnabled) {
            P2PPreferences.prefs(context).edit()
                .putBoolean(P2PPreferences.PROXY_ENABLED, false)
                .apply()
            settingsChanged()
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
                    title = if (isRussian) "Clearnet-трекеры (Интернет)" else "Clearnet trackers (Internet)",
                    subtitle = if (isRussian) {
                        "Публичные BitTorrent трекеры в обычном интернете"
                    } else {
                        "Public BitTorrent trackers over open internet"
                    },
                    checked = clearnetEnabled,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = {
                        TrackerPreferences.setClearnetTrackersEnabled(context, it)
                        settingsChanged()
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TrackerToggleRow(
                    title = if (isRussian) "Yggdrasil-трекеры (Mesh)" else "Yggdrasil trackers (Mesh)",
                    subtitle = if (isRussian) {
                        "Анонсы внутри зашифрованной сети Yggdrasil"
                    } else {
                        "Announces inside encrypted Yggdrasil network"
                    },
                    checked = yggEnabled,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = {
                        TrackerPreferences.setYggTrackersEnabled(context, it)
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
                text = if (isRussian) "Встроенная анонимизация (Tor)" else "Embedded Tor Privacy",
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TrackerSectionCard(surfaceColor, onSurfaceColor) {
                TrackerToggleRow(
                    title = if (isRussian) "Встроенный Tor (Автономно)" else "Embedded Tor (Autonomous)",
                    subtitle = if (isRussian) {
                        "Запуск фонового демона Tor без использования сторонних приложений"
                    } else {
                        "Launches autonomous embedded Tor daemon without external apps"
                    },
                    checked = torUserRequested || (proxyEnabled && isTorRunning),
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        torUserRequested = enabled
                        if (enabled) {
                            TorManager.startTor(context)
                        } else {
                            TorManager.stopTor()
                            P2PPreferences.prefs(context).edit()
                                .putBoolean(P2PPreferences.PROXY_ENABLED, false)
                                .apply()
                            settingsChanged()
                        }
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isConnecting = torUserRequested && !isTorRunning
                    Text(
                        text = if (isRussian) "Статус сеанса Tor" else "Tor Session Status",
                        fontSize = 14.sp,
                        color = onSurfaceColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = TorStatusFormatter.formatStatus(isRunning = isTorRunning, isConnecting = isConnecting, isRussian = isRussian),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isTorRunning -> Color(0xFF4CAF50)
                            isConnecting -> Color(0xFFFFD54F)
                            else -> onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isRussian) "SOCKS5 / Внешний Прокси" else "SOCKS5 / Custom Proxy",
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TrackerSectionCard(surfaceColor, onSurfaceColor) {
                var currentHost by remember(proxyHost) { mutableStateOf(proxyHost) }
                var currentPortText by remember(proxyPortText) { mutableStateOf(proxyPortText) }

                TrackerToggleRow(
                    title = if (isRussian) "Использовать SOCKS5 Прокси" else "Use SOCKS5 Proxy",
                    subtitle = if (isRussian) {
                        "Маршрутизация анонсов к трекерам через Tor (Orbot) или локальный прокси"
                    } else {
                        "Routes tracker announces through Tor (Orbot) or local proxy"
                    },
                    checked = proxyEnabled,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        P2PPreferences.prefs(context).edit().putBoolean(P2PPreferences.PROXY_ENABLED, enabled).commit()
                        settingsChanged()
                    },
                )
                if (proxyEnabled) {
                    HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = currentHost,
                            onValueChange = { newHost ->
                                currentHost = newHost
                                if (ProxyConfig.isValidHost(newHost)) {
                                    P2PPreferences.prefs(context).edit().putString(P2PPreferences.PROXY_HOST, newHost.trim()).commit()
                                    settingsChanged()
                                }
                            },
                            label = { Text(if (isRussian) "Хост прокси" else "Proxy Host") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = currentPortText,
                            onValueChange = { newPort ->
                                currentPortText = newPort
                                val portInt = newPort.toIntOrNull()
                                if (portInt != null && ProxyConfig.isValidPort(portInt)) {
                                    P2PPreferences.prefs(context).edit().putInt(P2PPreferences.PROXY_PORT, portInt).commit()
                                    settingsChanged()
                                }
                            },
                            label = { Text(if (isRussian) "Порт" else "Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isRussian) "Политика публикации IPv4" else "IPv4 Announce Policy",
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TrackerSectionCard(surfaceColor, onSurfaceColor) {
                TrackerToggleRow(
                    title = if (isRussian) "Авто (Рекомендуется)" else "Auto (Recommended)",
                    subtitle = if (isRussian) {
                        "Скрывать IPv4 адрес при активном Yggdrasil IPv6"
                    } else {
                        "Hide IPv4 address when Yggdrasil IPv6 is active"
                    },
                    checked = ipv4Mode == "auto",
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { checked ->
                        val targetMode = if (checked) "auto" else "never"
                        TrackerPreferences.setIpv4AnnounceMode(context, targetMode)
                        settingsChanged()
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TrackerToggleRow(
                    title = if (isRussian) "Никогда не публиковать IPv4" else "Never announce IPv4",
                    subtitle = if (isRussian) {
                        "Анонсировать только IPv6 / Yggdrasil (максимальная анонимность)"
                    } else {
                        "Announce IPv6 / Yggdrasil only (maximum anonymity)"
                    },
                    checked = ipv4Mode == "never",
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { checked ->
                        val targetMode = if (checked) "never" else "auto"
                        TrackerPreferences.setIpv4AnnounceMode(context, targetMode)
                        settingsChanged()
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TrackerToggleRow(
                    title = if (isRussian) "Всегда публиковать IPv4" else "Always announce IPv4",
                    subtitle = if (isRussian) {
                        "Публиковать IPv4 и IPv6 всегда (максимальная P2P-связность)"
                    } else {
                        "Always publish IPv4 and IPv6 (maximum P2P connectivity)"
                    },
                    checked = ipv4Mode == "always",
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { checked ->
                        val targetMode = if (checked) "always" else "auto"
                        TrackerPreferences.setIpv4AnnounceMode(context, targetMode)
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
