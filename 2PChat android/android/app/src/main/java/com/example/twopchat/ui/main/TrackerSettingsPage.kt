package com.example.twopchat.ui.main

import android.widget.Toast

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
import com.example.twopchat.TorBridgeCatalog
import com.example.twopchat.TorManager
import com.example.twopchat.TorBridgeValidationError
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
    val isTorConnecting by TorManager.isTorConnecting.collectAsState()
    val torBootstrapProgress by TorManager.bootstrapProgress.collectAsState()
    val torBootstrapFailure by TorManager.lastBootstrapFailureReason.collectAsState()
    var torUserRequested by remember {
        mutableStateOf(P2PPreferences.isTorEnabled(context) || isTorRunning || isTorConnecting)
    }
    var savedTorBridgeLines by remember {
        mutableStateOf(P2PPreferences.getTorBridgeLines(context))
    }
    var torBridgesText by remember {
        mutableStateOf(savedTorBridgeLines.joinToString("\n"))
    }
    var torBridgeSaveFailed by remember { mutableStateOf(false) }
    var publicTorBridgesEnabled by remember {
        mutableStateOf(P2PPreferences.publicTorBridgesEnabled(context))
    }
    val torBridgeValidation = remember(torBridgesText) {
        TorManager.parseBridgeText(torBridgesText)
    }
    val effectiveTorBridges = remember(torBridgeValidation, publicTorBridgesEnabled) {
        TorBridgeCatalog.select(
            customBridges = torBridgeValidation.bridges,
            publicBridgesEnabled = publicTorBridgesEnabled,
        )
    }

    fun settingsChanged() {
        revision += 1
        scope.launch(Dispatchers.IO) {
            PythonBridge.applyTrackerConfiguration()
            PythonBridge.applyProxyConfiguration()
            P2PMessageRelay.refreshAnnouncement(context)
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
                .putBoolean(P2PPreferences.PROXY_ENABLED, true)
                .putBoolean(P2PPreferences.TOR_ENABLED, true)
                .putString(P2PPreferences.PROXY_HOST, "127.0.0.1")
                .putInt(P2PPreferences.PROXY_PORT, 9050)
                .apply()
            settingsChanged()
        }
    }

    LaunchedEffect(torBootstrapFailure) {
        if (torBootstrapFailure != null && torUserRequested) {
            torUserRequested = false
            P2PPreferences.prefs(context).edit()
                .putBoolean(P2PPreferences.TOR_ENABLED, false)
                .putBoolean(P2PPreferences.PROXY_ENABLED, false)
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
                    onCheckedChange = torToggle@{ enabled ->
                        torUserRequested = enabled
                        if (enabled) {
                            if (torBridgeValidation.error != null) {
                                torUserRequested = false
                                Toast.makeText(
                                    context,
                                    torBridgeValidationMessage(torBridgeValidation.error, isRussian),
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@torToggle
                            }
                            if (!P2PPreferences.setTorBridgeLines(context, torBridgeValidation.bridges)) {
                                torUserRequested = false
                                torBridgeSaveFailed = true
                                return@torToggle
                            }
                            savedTorBridgeLines = torBridgeValidation.bridges
                            torBridgesText = torBridgeValidation.bridges.joinToString("\n")
                            torBridgeSaveFailed = false
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
                                .putBoolean(P2PPreferences.PROXY_ENABLED, false)
                                .apply()
                            settingsChanged()
                        }
                    },
                )
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
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
                                customBridges = torBridgeValidation.bridges,
                                publicBridgesEnabled = enabled,
                            )
                            if (
                                updated != previous &&
                                (torUserRequested || isTorRunning || isTorConnecting)
                            ) {
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
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = torBridgesText,
                        onValueChange = {
                            torBridgesText = it
                            torBridgeSaveFailed = false
                        },
                        label = {
                            Text(
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Свои мосты Tor (необязательно)",
                                    en = "Custom Tor Bridges (optional)",
                                    de = "Eigene Tor-Brücken (optional)",
                                    es = "Puentes Tor personalizados (opcional)",
                                    fr = "Ponts Tor personnalisés (optionnel)",
                                    pt = "Pontes Tor personalizadas (opcional)"
                                )
                            )
                        },
                        placeholder = {
                            Text("obfs4 IP:port fingerprint cert=… iat-mode=0")
                        },
                        minLines = 3,
                        maxLines = 6,
                        isError = torBridgeValidation.error != null || torBridgeSaveFailed,
                        supportingText = {
                            Text(
                                when {
                                    torBridgeSaveFailed -> com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "Не удалось сохранить мосты в защищённых настройках",
                                        en = "Could not save bridges to secure settings",
                                        de = "Brücken konnten nicht in sicheren Einstellungen gespeichert werden",
                                        es = "No se pudieron guardar los puentes en la configuración segura",
                                        fr = "Impossible d'enregistrer les ponts dans les paramètres sécurisés",
                                        pt = "Não foi possível salvar as pontes nas configurações seguras"
                                    )
                                    torBridgeValidation.error != null ->
                                        torBridgeValidationMessage(torBridgeValidation.error, isRussian)
                                    torBridgeValidation.bridges.isEmpty() && publicTorBridgesEnabled ->
                                        com.example.twopchat.data.Localizations.tr(
                                            appLanguage,
                                            ru = "Будет автоматически выбран рабочий публичный мост obfs4",
                                            en = "A working public obfs4 bridge will be selected automatically",
                                            de = "Eine funktionierende öffentliche obfs4-Brücke wird automatisch ausgewählt",
                                            es = "Se seleccionará automáticamente un puente obfs4 público que funcione",
                                            fr = "Un pont obfs4 public fonctionnel sera sélectionné automatiquement",
                                            pt = "Uma ponte obfs4 pública funcional será selecionada automaticamente"
                                        )
                                    torBridgeValidation.bridges.isEmpty() ->
                                        com.example.twopchat.data.Localizations.tr(
                                            appLanguage,
                                            ru = "Автомосты выключены: Tor попробует прямое подключение",
                                            en = "Automatic bridges are off: Tor will try a direct connection",
                                            de = "Automatische Brücken sind aus: Tor versucht eine direkte Verbindung",
                                            es = "Puentes automáticos desactivados: Tor intentará conexión directa",
                                            fr = "Ponts automatiques désactivés: Tor essaiera une connexion directe",
                                            pt = "Pontes automáticas desativadas: O Tor tentará conexão direta"
                                        )
                                    else -> com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "По одной строке obfs4 или snowflake на строку",
                                        en = "One obfs4 or snowflake bridge per line",
                                        de = "Eine obfs4- oder snowflake-Brücke pro Zeile",
                                        es = "Un puente obfs4 o snowflake por línea",
                                        fr = "Un pont obfs4 ou snowflake par ligne",
                                        pt = "Uma ponte obfs4 ou snowflake por linha"
                                    )
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled = torBridgeValidation.error == null &&
                            (torBridgeValidation.bridges != savedTorBridgeLines || torBridgeSaveFailed),
                        onClick = {
                            val saved = P2PPreferences.setTorBridgeLines(
                                context,
                                torBridgeValidation.bridges,
                            )
                            torBridgeSaveFailed = !saved
                            if (saved) {
                                savedTorBridgeLines = torBridgeValidation.bridges
                                torBridgesText = torBridgeValidation.bridges.joinToString("\n")
                                if (torUserRequested || isTorRunning || isTorConnecting) {
                                    TorManager.stopTor()
                                    TorManager.startTor(
                                        context,
                                        TorBridgeCatalog.select(
                                            customBridges = torBridgeValidation.bridges,
                                            publicBridgesEnabled = publicTorBridgesEnabled,
                                        ),
                                    )
                                }
                                Toast.makeText(
                                    context,
                                    com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "Мосты Tor сохранены",
                                        en = "Tor bridges saved",
                                        de = "Tor-Brücken gespeichert",
                                        es = "Puentes Tor guardados",
                                        fr = "Ponts Tor enregistrés",
                                        pt = "Pontes Tor salvas"
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Сохранить мосты",
                                en = "Save bridges",
                                de = "Brücken speichern",
                                es = "Guardar puentes",
                                fr = "Enregistrer les ponts",
                                pt = "Salvar pontes"
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = com.example.twopchat.data.Localizations.tr(
                    appLanguage,
                    ru = "SOCKS5 / Внешний Прокси",
                    en = "SOCKS5 / Custom Proxy",
                    de = "SOCKS5 / Externer Proxy",
                    es = "SOCKS5 / Proxy externo",
                    fr = "SOCKS5 / Proxy externe",
                    pt = "SOCKS5 / Proxy externo"
                ),
                color = onSurfaceColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TrackerSectionCard(surfaceColor, onSurfaceColor) {
                var currentHost by remember(proxyHost) { mutableStateOf(proxyHost) }
                var currentPortText by remember(proxyPortText) { mutableStateOf(proxyPortText) }

                TrackerToggleRow(
                    title = com.example.twopchat.data.Localizations.tr(
                        appLanguage,
                        ru = "Использовать SOCKS5 Прокси",
                        en = "Use SOCKS5 Proxy",
                        de = "SOCKS5-Proxy verwenden",
                        es = "Usar Proxy SOCKS5",
                        fr = "Utiliser un proxy SOCKS5",
                        pt = "Usar Proxy SOCKS5"
                    ),
                    subtitle = com.example.twopchat.data.Localizations.tr(
                        appLanguage,
                        ru = "Маршрутизация анонсов к трекерам через Tor (Orbot) или локальный прокси",
                        en = "Routes tracker announces through Tor (Orbot) or local proxy",
                        de = "Routet Tracker-Ankündigungen über Tor (Orbot) oder lokalen Proxy",
                        es = "Enruta anuncios de rastreo a través de Tor (Orbot) o proxy local",
                        fr = "Achemine les annonces de traqueurs via Tor (Orbot) ou un proxy local",
                        pt = "Roteia anúncios de rastreadores via Tor (Orbot) ou proxy local"
                    ),
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
