package com.example.twopchat.ui.main

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences
import com.example.twopchat.PythonBridge
import com.example.twopchat.TorBridgeCatalog

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
        if (torUserRequested && !isTorRunning) {
            TorManager.startTor(context)
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
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
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
                TrackerToggleRow(
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
                TrackerToggleRow(
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
                TrackerToggleRow(
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
                TrackerToggleRow(
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
                TrackerToggleRow(
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
