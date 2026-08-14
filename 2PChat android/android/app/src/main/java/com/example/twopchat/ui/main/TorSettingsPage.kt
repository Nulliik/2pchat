package com.example.twopchat.ui.main

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences
import com.example.twopchat.PythonBridge
import com.example.twopchat.TorBridgeCatalog
import com.example.twopchat.TorBridgeValidationError
import com.example.twopchat.TorManager
import com.example.twopchat.TorStatusFormatter
import com.example.twopchat.TorTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun TorSettingsPage(
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

    val isTorRunning by TorManager.isTorRunning.collectAsState()
    val isTorConnecting by TorManager.isTorConnecting.collectAsState()
    val torBootstrapProgress by TorManager.bootstrapProgress.collectAsState()
    val torBootstrapFailure by TorManager.lastBootstrapFailureReason.collectAsState()

    var torUserRequested by remember {
        mutableStateOf(P2PPreferences.isTorEnabled(context) || isTorRunning || isTorConnecting)
    }
    var savedBridgeLines by remember {
        mutableStateOf(P2PPreferences.getTorBridgeLines(context))
    }
    var showAddBridgeDialog by remember { mutableStateOf(false) }
    var bridgePanelExpanded by remember { mutableStateOf(savedBridgeLines.isNotEmpty()) }
    var publicTorBridgesEnabled by remember {
        mutableStateOf(P2PPreferences.publicTorBridgesEnabled(context))
    }
    var torTransport by remember { mutableStateOf(P2PPreferences.torTransport(context)) }

    val effectiveTorBridges = remember(savedBridgeLines, publicTorBridgesEnabled, torTransport) {
        TorBridgeCatalog.select(
            customBridges = savedBridgeLines,
            publicBridgesEnabled = publicTorBridgesEnabled,
            transport = torTransport,
        )
    }

    fun settingsChanged() {
        scope.launch(Dispatchers.IO) {
            PythonBridge.applyTrackerConfiguration()
            PythonBridge.updateNetworkProxy(context)
            P2PMessageRelay.refreshAnnouncement(context)
        }
    }

    fun restartTorForTransport(transport: TorTransport) {
        if (torUserRequested || isTorRunning || isTorConnecting) {
            TorManager.stopTor()
            TorManager.startTor(
                context,
                TorBridgeCatalog.select(
                    customBridges = savedBridgeLines,
                    publicBridgesEnabled = publicTorBridgesEnabled,
                    transport = transport,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        val torEnabled = P2PPreferences.isTorEnabled(context)
        if (torEnabled && !isTorRunning && !isTorConnecting) {
            TorManager.startTor(context, effectiveTorBridges)
        }
    }

    LaunchedEffect(isTorRunning) {
        if (isTorRunning) {
            torUserRequested = true
            P2PPreferences.prefs(context).edit()
                .putBoolean(P2PPreferences.TOR_ENABLED, true)
                .apply()
            settingsChanged()
        }
    }

    LaunchedEffect(torBootstrapFailure) {
        if (torBootstrapFailure != null && torUserRequested) {
            torUserRequested = false
            P2PPreferences.prefs(context).edit()
                .putBoolean(P2PPreferences.TOR_ENABLED, false)
                .apply()
            settingsChanged()
            Toast.makeText(
                context,
                TorStatusFormatter.getFailedToast(appLanguage),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        SubPageLayout(
            title = if (isRussian) "Tor и анонимность" else "Tor & Anonymity",
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
        ) {
            // ── Main toggle + status ────────────────────────────────────
            TorInnerCard(surfaceColor, onSurfaceColor) {
                TorToggleRow(
                    title = if (isRussian) "Встроенный Tor (Автономно)" else "Embedded Tor (Autonomous)",
                    subtitle = if (isRussian) {
                        "Запуск фонового демона Tor без использования сторонних приложений"
                    } else {
                        "Launches autonomous embedded Tor daemon without external apps"
                    },
                    checked = torUserRequested || isTorRunning,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        torUserRequested = enabled
                        if (enabled) {
                            P2PPreferences.prefs(context).edit()
                                .putBoolean(P2PPreferences.TOR_ENABLED, true)
                                .apply()
                            Toast.makeText(
                                context,
                                TorStatusFormatter.getActivationToast(appLanguage),
                                Toast.LENGTH_LONG
                            ).show()
                            TorManager.startTor(context, effectiveTorBridges)
                        } else {
                            TorManager.stopTor()
                            P2PPreferences.prefs(context).edit()
                                .putBoolean(P2PPreferences.TOR_ENABLED, false)
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
                    Text(
                        text = if (isRussian) "Статус сеанса Tor" else "Tor Session Status",
                        fontSize = 14.sp,
                        color = onSurfaceColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = TorStatusFormatter.formatStatus(
                            isRunning = isTorRunning,
                            isConnecting = isTorConnecting,
                            appLanguage = appLanguage,
                            progress = torBootstrapProgress,
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isTorRunning -> Color(0xFF4CAF50)
                            isTorConnecting -> Color(0xFFFFD54F)
                            else -> onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
            }

            // ── Transport ───────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isRussian) "Транспорт" else "Transport",
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TorInnerCard(surfaceColor, onSurfaceColor) {
                TorToggleRow(
                    title = if (isRussian) "Автовыбор транспорта" else "Automatic transport selection",
                    subtitle = if (isRussian) {
                        "Сначала obfs4, затем Snowflake при недоступности мостов"
                    } else {
                        "Try obfs4 first, then Snowflake if bridges are unreachable"
                    },
                    checked = torTransport == TorTransport.AUTO,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        if (enabled && P2PPreferences.setTorTransport(context, TorTransport.AUTO)) {
                            torTransport = TorTransport.AUTO
                            restartTorForTransport(TorTransport.AUTO)
                            settingsChanged()
                        }
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TorToggleRow(
                    title = "obfs4",
                    subtitle = if (isRussian) "Использовать только встроенный пул obfs4" else "Use only the built-in obfs4 pool",
                    checked = torTransport == TorTransport.OBFS4,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        if (enabled && P2PPreferences.setTorTransport(context, TorTransport.OBFS4)) {
                            torTransport = TorTransport.OBFS4
                            restartTorForTransport(TorTransport.OBFS4)
                            settingsChanged()
                        }
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TorToggleRow(
                    title = "Snowflake",
                    subtitle = if (isRussian) {
                        "WebRTC-прокси волонтёров для сетей с глубокой блокировкой"
                    } else {
                        "Volunteer WebRTC proxies for heavily censored networks"
                    },
                    checked = torTransport == TorTransport.SNOWFLAKE,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        if (enabled && P2PPreferences.setTorTransport(context, TorTransport.SNOWFLAKE)) {
                            torTransport = TorTransport.SNOWFLAKE
                            restartTorForTransport(TorTransport.SNOWFLAKE)
                            settingsChanged()
                        }
                    },
                )
            }

            // ── Bridges ─────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isRussian) "Мосты (Bridges)" else "Bridges",
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TorInnerCard(surfaceColor, onSurfaceColor) {
                TorToggleRow(
                    title = if (isRussian) "Автоматические публичные мосты" else "Automatic public bridges",
                    subtitle = if (isRussian) {
                        "Встроенный пул obfs4 проверяется автоматически; ручные мосты имеют приоритет"
                    } else {
                        "The built-in obfs4 pool is checked automatically; custom bridges take priority"
                    },
                    checked = publicTorBridgesEnabled,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onCheckedChange = { enabled ->
                        if (P2PPreferences.setPublicTorBridgesEnabled(context, enabled)) {
                            val previous = effectiveTorBridges
                            publicTorBridgesEnabled = enabled
                            val updated = TorBridgeCatalog.select(
                                customBridges = savedBridgeLines,
                                publicBridgesEnabled = enabled,
                                transport = torTransport,
                            )
                            if (updated != previous && (torUserRequested || isTorRunning || isTorConnecting)) {
                                TorManager.stopTor()
                                TorManager.startTor(context, updated)
                            }
                        } else {
                            Toast.makeText(
                                context,
                                if (isRussian) "Не удалось сохранить режим мостов" else "Could not save bridge mode",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                TorBridgePanel(
                    bridges = savedBridgeLines,
                    expanded = bridgePanelExpanded,
                    onExpandToggle = { bridgePanelExpanded = !bridgePanelExpanded },
                    onAddClick = { showAddBridgeDialog = true },
                    onDelete = { line ->
                        val updated = savedBridgeLines.filter { it != line }
                        if (P2PPreferences.setTorBridgeLines(context, updated)) {
                            savedBridgeLines = updated
                            if (torUserRequested || isTorRunning || isTorConnecting) {
                                TorManager.stopTor()
                                TorManager.startTor(
                                    context,
                                    TorBridgeCatalog.select(
                                        customBridges = updated,
                                        publicBridgesEnabled = publicTorBridgesEnabled,
                                        transport = torTransport,
                                    ),
                                )
                            }
                        } else {
                            Toast.makeText(
                                context,
                                if (isRussian) "Не удалось удалить мост" else "Could not remove bridge",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    isRussian = isRussian,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    primaryColor = primaryColor,
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showAddBridgeDialog) {
        AddBridgeDialog(
            isRussian = isRussian,
            existingBridges = savedBridgeLines,
            onDismiss = { showAddBridgeDialog = false },
            onAdd = { newLine ->
                val parseResult = TorManager.parseBridgeText(newLine)
                if (parseResult.error != null) {
                    torBridgeValidationMessage(parseResult.error, isRussian)
                } else if (parseResult.bridges.isEmpty()) {
                    if (isRussian) "Введите строку моста" else "Please enter a bridge line"
                } else {
                    val updated = (savedBridgeLines + parseResult.bridges).distinct()
                    if (P2PPreferences.setTorBridgeLines(context, updated)) {
                        savedBridgeLines = updated
                        bridgePanelExpanded = true
                        showAddBridgeDialog = false
                        if (torUserRequested || isTorRunning || isTorConnecting) {
                            TorManager.stopTor()
                            TorManager.startTor(
                                context,
                                TorBridgeCatalog.select(
                                    customBridges = updated,
                                    publicBridgesEnabled = publicTorBridgesEnabled,
                                    transport = torTransport,
                                ),
                            )
                        }
                        null
                    } else {
                        if (isRussian) "Не удалось сохранить мосты" else "Could not save bridges"
                    }
                }
            },
        )
    }
}

@Composable
private fun TorToggleRow(
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

private fun torBridgeValidationMessage(
    error: TorBridgeValidationError?,
    isRussian: Boolean,
): String = when (error) {
    TorBridgeValidationError.INPUT_TOO_LARGE,
    TorBridgeValidationError.TOO_MANY_BRIDGES,
    TorBridgeValidationError.LINE_TOO_LONG -> if (isRussian) {
        "Список мостов слишком большой"
    } else {
        "The bridge list is too large"
    }

    TorBridgeValidationError.UNSUPPORTED_TRANSPORT -> if (isRussian) {
        "Поддерживаются только мосты obfs4 и snowflake"
    } else {
        "Only obfs4 and snowflake bridges are supported"
    }

    TorBridgeValidationError.INVALID_ENDPOINT -> if (isRussian) {
        "Проверьте адрес и порт моста"
    } else {
        "Check the bridge address and port"
    }

    TorBridgeValidationError.INVALID_FINGERPRINT -> if (isRussian) {
        "Fingerprint моста должен содержать 40 шестнадцатеричных символов"
    } else {
        "The bridge fingerprint must contain 40 hexadecimal characters"
    }

    TorBridgeValidationError.MISSING_OBFS4_CERT,
    TorBridgeValidationError.INVALID_OBFS4_IAT_MODE -> if (isRussian) {
        "Строка obfs4 должна содержать корректные cert и iat-mode"
    } else {
        "The obfs4 line must contain valid cert and iat-mode values"
    }

    TorBridgeValidationError.MISSING_SNOWFLAKE_CONFIGURATION -> if (isRussian) {
        "Строка snowflake должна содержать url, front/fronts и ice"
    } else {
        "The snowflake line must contain url, front/fronts, and ice"
    }

    TorBridgeValidationError.INVALID_FORMAT -> if (isRussian) {
        "Некорректный формат строки моста"
    } else {
        "Invalid bridge-line format"
    }

    null -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TorBridgePanel(
    bridges: List<String>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onAddClick: () -> Unit,
    onDelete: (String) -> Unit,
    isRussian: Boolean,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
) {
    val arrowAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpandToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRussian) "Свои мосты Tor" else "Custom Tor Bridges",
                    color = onSurfaceColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
                Text(
                    text = if (bridges.isEmpty()) {
                        if (isRussian) "Нет добавленных мостов" else "No bridges added"
                    } else {
                        if (isRussian) "${bridges.size} ${pluralRu(bridges.size, "мост", "моста", "мостов")}" else "${bridges.size} bridge${if (bridges.size == 1) "" else "s"}"
                    },
                    color = onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAddClick) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isRussian) "Добавить мост" else "Add bridge",
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowAngle),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (bridges.isEmpty()) {
                    Text(
                        text = if (isRussian) {
                            "Нажмите «+» чтобы добавить obfs4 или snowflake мост"
                        } else {
                            "Tap «+» to add an obfs4 or snowflake bridge"
                        },
                        color = onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        bridges.forEach { line ->
                            val label = line.take(32).let { if (line.length > 32) "$it…" else it }
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onDelete(line) },
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = if (isRussian) "Удалить" else "Delete",
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = onSurfaceColor.copy(alpha = 0.07f),
                                    labelColor = onSurfaceColor,
                                    trailingIconColor = onSurfaceVariant,
                                ),
                                border = null,
                            )
                        }
                    }
                }
                AssistChip(
                    onClick = onAddClick,
                    label = { Text(if (isRussian) "Добавить мост" else "Add bridge", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = primaryColor.copy(alpha = 0.12f),
                        labelColor = primaryColor,
                        leadingIconContentColor = primaryColor,
                    ),
                    border = null,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AddBridgeDialog(
    isRussian: Boolean,
    existingBridges: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> String?,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRussian) "Добавить мост Tor" else "Add Tor Bridge") },
        text = {
            Column {
                Text(
                    text = if (isRussian) {
                        "Вставьте строку obfs4 или snowflake моста, полученную с bridges.torproject.org"
                    } else {
                        "Paste an obfs4 or snowflake bridge line obtained from bridges.torproject.org"
                    },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    placeholder = { Text("obfs4 1.2.3.4:1234 FINGERPRINT cert=… iat-mode=0", fontSize = 11.sp) },
                    minLines = 3,
                    maxLines = 6,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existingBridges.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isRussian) {
                            "Уже добавлено: ${existingBridges.size}"
                        } else {
                            "Already saved: ${existingBridges.size}"
                        },
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onAdd(text.trim())
                error = result
            }) {
                Text(if (isRussian) "Добавить" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isRussian) "Отмена" else "Cancel")
            }
        },
    )
}

private fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod100 in 11..19 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

@Composable
private fun TorInnerCard(
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
        Column { content() }
    }
}
