package com.example.twopchat.ui.main

import android.content.Intent
import com.example.twopchat.config.*
import com.example.twopchat.yggdrasil.YggdrasilCoordinator
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.twopchat.data.Localizations
import com.example.twopchat.yggdrasil.CustomYggdrasilPeer
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import com.example.twopchat.yggdrasil.YggdrasilPeerPreferences
import com.example.twopchat.yggdrasil.YggdrasilPeerSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

@Composable
fun YggdrasilPeerSettingsPage(
    appLanguage: String,
    onBackClick: () -> Unit,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRussian = appLanguage == "Русский"
    var revision by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRegenerateYggdrasilKeysDialog by remember { mutableStateOf(false) }
    var applyJob by remember { mutableStateOf<Job?>(null) }
    var applying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val sharedPrefs = remember(context) { P2PPreferences.prefs(context) }
    var currentMode by remember { mutableStateOf(P2PPreferences.getYggdrasilMode(context)) }
    var yggdrasilRouting by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_yggdrasil", false))
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                currentMode = P2PPreferences.YggdrasilMode.VPN
                P2PPreferences.setYggdrasilMode(context, P2PPreferences.YggdrasilMode.VPN)
                YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.VPN)
                yggdrasilRouting = true
                sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
            } else {
                yggdrasilRouting = false
                sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
            }
        }
    )

    DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("yggdrasil_") == true) revision++
            if (key == "settings_yggdrasil") {
                yggdrasilRouting = sharedPrefs.getBoolean("settings_yggdrasil", false)
            }
            if (key == P2PPreferences.YGGDRASIL_MODE) {
                currentMode = P2PPreferences.getYggdrasilMode(context)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val publicEnabled = remember(revision) {
        YggdrasilPeerPreferences.publicPeersEnabled(context)
    }
    val disabledPublic = remember(revision) {
        YggdrasilPeerPreferences.disabledPublicPeers(context)
    }
    val sort = remember(revision) { YggdrasilPeerPreferences.sort(context) }
    val publicPeers = remember(revision) {
        YggdrasilPeerPreferences.sortedPublicPeers(
            peers = YggdrasilPeerPreferences.publicPeers(context),
            disabledPeers = disabledPublic,
            sort = sort,
        )
    }
    val customPeers = remember(revision) {
        YggdrasilPeerPreferences.sortedCustomPeers(
            peers = YggdrasilPeerPreferences.customPeers(context),
            sort = sort,
        )
    }

    fun applyPeerSettings() {
        revision++
        applyError = null
        applyJob?.cancel()
        applyJob = scope.launch {
            delay(300)
            applying = true
            try {
                val yggdrasilEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                if (yggdrasilEnabled) {
                    YggdrasilCoordinator.reloadPeers(context)
                }
            } catch (_: Exception) {
                applyError = if (isRussian) {
                    "Не удалось применить настройки пиров"
                } else {
                    "Could not apply peer settings"
                }
            } finally {
                applying = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        SubPageLayout(
            title = if (isRussian) "Настройки Yggdrasil" else "Yggdrasil Settings",
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "yggdrasil_main_routing") {
                    PeerSettingsCard(surfaceColor, onSurfaceColor) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        Localizations.getString("yggdrasil_routing", appLanguage),
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor
                                    )
                                    Text(
                                        Localizations.getString("yggdrasil_routing_desc", appLanguage),
                                        fontSize = 12.sp,
                                        color = onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Switch(
                                    checked = yggdrasilRouting,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) {
                                            if (currentMode == P2PPreferences.YggdrasilMode.VPN) {
                                                val vpnIntent = VpnService.prepare(context)
                                                if (vpnIntent != null) {
                                                    vpnLauncher.launch(vpnIntent)
                                                } else {
                                                    YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.VPN)
                                                    yggdrasilRouting = true
                                                    sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
                                                }
                                            } else {
                                                YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.PROXY)
                                                yggdrasilRouting = true
                                                sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
                                            }
                                        } else {
                                            YggdrasilCoordinator.stop(context)
                                            yggdrasilRouting = false
                                            sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = primaryColor,
                                        checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                    )
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = onSurfaceColor.copy(alpha = 0.05f)
                            )

                            // Mode Selector
                            Text(
                                text = if (isRussian) "Режим работы" else "Operation Mode",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = onSurfaceColor
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = currentMode == P2PPreferences.YggdrasilMode.PROXY,
                                    onClick = {
                                        if (currentMode != P2PPreferences.YggdrasilMode.PROXY) {
                                            currentMode = P2PPreferences.YggdrasilMode.PROXY
                                            P2PPreferences.setYggdrasilMode(context, P2PPreferences.YggdrasilMode.PROXY)
                                            if (yggdrasilRouting) {
                                                YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.PROXY)
                                            }
                                        }
                                    },
                                    label = {
                                        Text(if (isRussian) "Proxy (Рекомендуется)" else "Proxy (Recommended)")
                                    }
                                )
                                FilterChip(
                                    selected = currentMode == P2PPreferences.YggdrasilMode.VPN,
                                    onClick = {
                                        if (currentMode != P2PPreferences.YggdrasilMode.VPN) {
                                            if (yggdrasilRouting) {
                                                val vpnIntent = VpnService.prepare(context)
                                                if (vpnIntent != null) {
                                                    vpnLauncher.launch(vpnIntent)
                                                } else {
                                                    currentMode = P2PPreferences.YggdrasilMode.VPN
                                                    P2PPreferences.setYggdrasilMode(context, P2PPreferences.YggdrasilMode.VPN)
                                                    YggdrasilCoordinator.start(context, P2PPreferences.YggdrasilMode.VPN)
                                                }
                                            } else {
                                                currentMode = P2PPreferences.YggdrasilMode.VPN
                                                P2PPreferences.setYggdrasilMode(context, P2PPreferences.YggdrasilMode.VPN)
                                            }
                                        }
                                    },
                                    label = {
                                        Text(if (isRussian) "Системный VPN" else "System VPN")
                                    }
                                )
                            }
                            val currentProxyAddr = P2PPreferences.getYggdrasilProxyAddr(context)
                            Text(
                                text = if (currentMode == P2PPreferences.YggdrasilMode.PROXY) {
                                    if (isRussian) "Фоновый SOCKS5 proxy ($currentProxyAddr). Не требует разрешений VPN и работает параллельно с любым VPN."
                                    else "Background SOCKS5 proxy ($currentProxyAddr). Requires no VPN permissions and coexists with other VPNs."
                                } else {
                                    if (isRussian) "Создает L3 TUN интерфейс через VpnService Android. Занимает системный VPN слот."
                                    else "Creates L3 TUN interface via Android VpnService. Occupies the system VPN slot."
                                },
                                fontSize = 11.sp,
                                color = onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = onSurfaceColor.copy(alpha = 0.05f)
                            )

                            TextButton(
                                onClick = { showRegenerateYggdrasilKeysDialog = true },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(
                                    if (isRussian) "Сгенерировать новый ключ Yggdrasil" else "Generate new Yggdrasil key"
                                )
                            }
                        }
                    }
                }

                item(key = "public_toggle") {
                    PeerSettingsCard(surfaceColor, onSurfaceColor) {
                        PeerToggleContent(
                            title = if (isRussian) "Использовать публичные пиры" else "Use public peers",
                            subtitle = if (isRussian) {
                                "Подключаться к автоматически выбранным публичным узлам Yggdrasil"
                            } else {
                                "Connect to automatically selected public Yggdrasil nodes"
                            },
                            checked = publicEnabled,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceVariant = onSurfaceVariant,
                            onCheckedChange = {
                                YggdrasilPeerPreferences.setPublicPeersEnabled(context, it)
                                applyPeerSettings()
                            },
                        )
                    }
                }

                item(key = "sort") {
                    Text(
                        text = if (isRussian) "Сортировка" else "Sort by",
                        color = onSurfaceColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        YggdrasilPeerSort.entries.forEach { option ->
                            FilterChip(
                                selected = sort == option,
                                onClick = {
                                    YggdrasilPeerPreferences.setSort(context, option)
                                    revision++
                                },
                                label = {
                                    Text(
                                        when (option) {
                                            YggdrasilPeerSort.STATUS -> if (isRussian) "Состояние" else "Status"
                                            YggdrasilPeerSort.ADDRESS -> if (isRussian) "Адрес" else "Address"
                                            YggdrasilPeerSort.PROTOCOL -> if (isRussian) "Протокол" else "Protocol"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                item(key = "public_header") {
                    Text(
                        text = if (isRussian) {
                            "Публичные пиры (${publicPeers.size})"
                        } else {
                            "Public peers (${publicPeers.size})"
                        },
                        color = onSurfaceColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                items(publicPeers, key = { "public:$it" }) { uri ->
                    val peerEnabled = disabledPublic.none { it.equals(uri, ignoreCase = true) }
                    PublicPeerRow(
                        uri = uri,
                        checked = publicEnabled && peerEnabled,
                        enabled = publicEnabled,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        onCheckedChange = {
                            YggdrasilPeerPreferences.setPublicPeerEnabled(context, uri, it)
                            applyPeerSettings()
                        },
                    )
                }

                item(key = "custom_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isRussian) "Мои пиры" else "My peers",
                            color = onSurfaceColor,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = { showAddDialog = true }) {
                            Text(if (isRussian) "Добавить" else "Add", color = primaryColor)
                        }
                    }
                }

                if (customPeers.isEmpty()) {
                    item(key = "custom_empty") {
                        Text(
                            text = if (isRussian) {
                                "Пользовательские пиры не добавлены"
                            } else {
                                "No custom peers added"
                            },
                            color = onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(customPeers, key = { "custom:${it.id}" }) { peer ->
                        CustomPeerRow(
                            peer = peer,
                            isRussian = isRussian,
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceVariant = onSurfaceVariant,
                            onEnabledChange = {
                                YggdrasilPeerPreferences.setCustomPeerEnabled(context, peer.id, it)
                                applyPeerSettings()
                            },
                            onDelete = {
                                YggdrasilPeerPreferences.deleteCustomPeer(context, peer.id)
                                applyPeerSettings()
                            },
                        )
                    }
                }

                if (applying) {
                    item(key = "applying") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (isRussian) "Применение настроек…" else "Applying settings…",
                                color = onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                applyError?.let { message ->
                    item(key = "apply_error") {
                        Text(
                            text = message,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                }

                item(key = "dpi_bypass") {
                    Button(
                        onClick = {
                            YggdrasilPeerPreferences.applyDpiBypassPeers(context)
                            applyPeerSettings()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        Text(
                            text = if (isRussian) "⚡ Обход блокировок портов (TLS 443)" else "⚡ Bypass Port Blocking (TLS 443)",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                item(key = "reset") {
                    Button(
                        onClick = {
                            YggdrasilPeerPreferences.resetDefaults(context)
                            applyPeerSettings()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 40.dp),
                    ) {
                        Text(if (isRussian) "Вернуть значения по умолчанию" else "Restore defaults")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddYggdrasilPeerDialog(
            isRussian = isRussian,
            onDismiss = { showAddDialog = false },
            onAdd = { name, uri ->
                val error = YggdrasilPeerPreferences.addCustomPeer(context, name, uri)
                if (error == null) {
                    showAddDialog = false
                    applyPeerSettings()
                }
                error
            },
        )
    }

    if (showRegenerateYggdrasilKeysDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateYggdrasilKeysDialog = false },
            title = {
                Text(if (isRussian) "Сгенерировать новый ключ Yggdrasil?" else "Generate a new Yggdrasil key?")
            },
            text = {
                Text(
                    if (isRussian) {
                        "Текущий Yggdrasil IPv6 изменится. Сохранённые у контактов старые адреса перестанут работать."
                    } else {
                        "Your Yggdrasil IPv6 address will change. Contacts with the old address will no longer be able to reach you."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        YggdrasilCoordinator.regenerateKeys(context)
                    }
                    showRegenerateYggdrasilKeysDialog = false
                    Toast.makeText(
                        context,
                        if (isRussian) "Yggdrasil-ключ обновлён" else "Yggdrasil key regenerated",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(if (isRussian) "Сгенерировать" else "Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateYggdrasilKeysDialog = false }) {
                    Text(if (isRussian) "Отмена" else "Cancel")
                }
            },
        )
    }
}

@Composable
private fun PeerSettingsCard(
    surfaceColor: Color,
    onSurfaceColor: Color,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp)),
    ) {
        content()
    }
}

@Composable
private fun PeerToggleContent(
    title: String,
    subtitle: String,
    checked: Boolean,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = onSurfaceColor.copy(alpha = if (enabled) 1f else 0.45f),
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle,
                color = onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PublicPeerRow(
    uri: String,
    checked: Boolean,
    enabled: Boolean,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    PeerSettingsCard(surfaceColor, onSurfaceColor) {
        PeerToggleContent(
            title = peerTitle(uri),
            subtitle = uri,
            checked = checked,
            enabled = enabled,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun CustomPeerRow(
    peer: CustomYggdrasilPeer,
    isRussian: Boolean,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    PeerSettingsCard(surfaceColor, onSurfaceColor) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.name, color = onSurfaceColor, fontWeight = FontWeight.Medium)
                    Text(
                        peer.uri,
                        color = onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = peer.enabled, onCheckedChange = onEnabledChange)
            }
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                Text(if (isRussian) "Удалить" else "Delete")
            }
        }
    }
}

@Composable
private fun AddYggdrasilPeerDialog(
    isRussian: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> String?,
) {
    val dialogContext = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRussian) "Добавить пир Yggdrasil" else "Add Yggdrasil peer") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = dialogContext,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    ),
                    label = { Text(if (isRussian) "Название" else "Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it; error = null },
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = dialogContext,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                    label = { Text("URI") },
                    placeholder = { Text("tls://peer.example:443") },
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
            TextButton(onClick = { error = onAdd(name, uri) }) {
                Text(if (isRussian) "Добавить" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isRussian) "Отмена" else "Cancel") }
        },
    )
}

private fun peerTitle(uri: String): String = try {
    val parsed = URI(uri)
    "${parsed.host}:${parsed.port} · ${parsed.scheme.uppercase()}"
} catch (_: Exception) {
    uri
}
