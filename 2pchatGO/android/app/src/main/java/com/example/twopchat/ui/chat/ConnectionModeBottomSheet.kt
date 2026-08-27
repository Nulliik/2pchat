package com.example.twopchat.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.config.P2PPreferences.PeerTransportPreference
import com.example.twopchat.R
import com.example.twopchat.tor.*
import com.example.twopchat.relay.TransportType
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionModeBottomSheet(
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val isRussian = appLanguage == "Русский"
    var currentPreference by remember(peerName) {
        mutableStateOf(P2PPreferences.getPeerTransportPreference(context, peerName))
    }

    val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
    val activeTransportType = P2PMessageRelay.getPeerTransportType(peerName)
    val rttMs = P2PMessageRelay.peerRttMs[peerName]
    val activeEndpoint = P2PMessageRelay.peerEndpoints[peerName].orEmpty()
    val isTorRunning by TorManager.isTorRunning.collectAsState()
    val isRotatingCircuit by TorManager.isRotatingCircuit.collectAsState()

    var isReconnecting by remember { mutableStateOf(false) }
    var isEndpointsExpanded by remember(peerName) { mutableStateOf(false) }

    fun selectMode(mode: PeerTransportPreference) {
        currentPreference = mode
        P2PPreferences.setPeerTransportPreference(context, peerName, mode)
        if (mode == PeerTransportPreference.TOR_ONLY) {
            if (!isTorRunning) {
                TorManager.startTor(context)
            }
            if (P2PPreferences.getPeerOnionAddress(context, peerName) == null) {
                scope.launch(Dispatchers.IO) {
                    val foundOnion: String? = runCatching {
                        com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).getMessagesForPeer(peerName)
                            .asSequence()
                            .map { it.text }
                            .mapNotNull { text: String -> Regex("""([a-z2-7]{56}\.onion(?::\d+)?)""", RegexOption.IGNORE_CASE).find(text)?.value }
                            .firstOrNull()
                    }.getOrNull()
                    if (foundOnion != null) {
                        val formatted = com.example.twopchat.ui.main.formatInviteEndpoint(foundOnion, P2PMessageRelay.listenerPort(context))
                        if (formatted != null && formatted.contains(".onion", ignoreCase = true)) {
                            P2PPreferences.setPeerOnionAddress(context, peerName, formatted)
                            val fingerprint = P2PPreferences.prefs(context).getString("peer_fingerprint_$peerName", null)
                            com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).savePeerOnionAddress(
                                peerName = peerName,
                                onionAddress = formatted,
                                fingerprint = fingerprint,
                                endpoint = activeEndpoint,
                            )
                        }
                    }
                }
            }
        }
        when (mode) {
            PeerTransportPreference.TOR_ONLY -> {
                P2PMessageRelay.peerConnectionTransports[peerName] = "Tor Onion"
                P2PPreferences.prefs(context).edit().putString(P2PPreferences.transport(peerName), "Tor Onion").apply()
            }
            PeerTransportPreference.YGGDRASIL_ONLY -> {
                P2PMessageRelay.peerConnectionTransports[peerName] = "Yggdrasil"
                P2PPreferences.prefs(context).edit().putString(P2PPreferences.transport(peerName), "Yggdrasil").apply()
            }
            PeerTransportPreference.DIRECT_ONLY -> {
                P2PMessageRelay.peerConnectionTransports[peerName] = "Direct P2P"
                P2PPreferences.prefs(context).edit().putString(P2PPreferences.transport(peerName), "Direct P2P").apply()
            }
            PeerTransportPreference.AUTO -> {}
        }
        scope.launch {
            isReconnecting = true
            P2PMessageRelay.reconnectSession(context, peerName) {
                isReconnecting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = surfaceColor,
        contentColor = onSurfaceColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .background(onSurfaceVariant.copy(alpha = 0.25f), CircleShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title & Peer Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(primaryColor.copy(alpha = 0.20f), primaryColor.copy(alpha = 0.05f))
                            )
                        )
                        .border(1.dp, primaryColor.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_status),
                        contentDescription = "Security",
                        tint = primaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRussian) "Режим соединения" else "Connection Mode",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isRussian) "Маршрут связи с $peerName" else "Routing for $peerName",
                        fontSize = 13.sp,
                        color = onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Real-Time Telemetry & Status Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                primaryColor.copy(alpha = 0.08f),
                                onSurfaceColor.copy(alpha = 0.03f)
                            )
                        ),
                        RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, primaryColor.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Connection Status Pill
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isOnline -> Color(0xFF10B981)
                                            isReconnecting -> Color(0xFFF59E0B)
                                            else -> onSurfaceVariant.copy(alpha = 0.50f)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isReconnecting -> if (isRussian) "Подключение..." else "Connecting..."
                                    isOnline -> if (isRussian) "В сети" else "Online"
                                    else -> if (isRussian) "Не в сети" else "Offline"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isOnline) Color(0xFF10B981) else onSurfaceColor
                            )
                        }

                        // Active Transport Badge
                        val badgeColor = when (activeTransportType) {
                            TransportType.ONION -> Color(0xFFA78BFA)
                            TransportType.DIRECT, TransportType.YGGDRASIL -> Color(0xFF10B981)
                            TransportType.DISCONNECTED -> onSurfaceVariant
                        }
                        val badgeBg = when (activeTransportType) {
                            TransportType.ONION -> Color(0xFF7C3AED).copy(alpha = 0.20f)
                            TransportType.DIRECT, TransportType.YGGDRASIL -> Color(0xFF10B981).copy(alpha = 0.15f)
                            TransportType.DISCONNECTED -> onSurfaceVariant.copy(alpha = 0.10f)
                        }
                        val badgeText = when (activeTransportType) {
                            TransportType.ONION -> "Tor Onion"
                            TransportType.DIRECT -> "Direct P2P"
                            TransportType.YGGDRASIL -> "Yggdrasil"
                            TransportType.DISCONNECTED -> if (isRussian) "Ожидание" else "Idle"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            if (activeTransportType == TransportType.ONION) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_tor),
                                    contentDescription = "Tor",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = badgeText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                            if (rttMs != null && isOnline) {
                                Text(
                                    text = " • ${rttMs}ms",
                                    fontSize = 11.sp,
                                    color = badgeColor.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // E2EE & Security Note
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🔒 Double Ratchet E2EE • Forward Secrecy",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor
                        )
                    }

                    val rawEndpoints = activeEndpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val allEndpoints = mutableListOf<String>()
                    for (ep in rawEndpoints) {
                        if (isValidPeerEndpoint(ep) && ep !in allEndpoints) allEndpoints.add(ep)
                    }
                    val savedOnion = P2PPreferences.getPeerOnionAddress(context, peerName)
                    if (!savedOnion.isNullOrBlank() && isValidPeerEndpoint(savedOnion) && savedOnion !in allEndpoints) {
                        allEndpoints.add(savedOnion)
                    }

                    // Sort: Tor Onion & Yggdrasil IPv6 first, then Direct IPv4
                    allEndpoints.sortWith(compareBy { ep ->
                        when {
                            ep.contains(".onion", ignoreCase = true) -> 0
                            ep.startsWith("[") || ep.count { it == ':' } > 1 -> 1
                            else -> 2
                        }
                    })

                    if (allEndpoints.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = Localizations.tr(
                                    language = appLanguage,
                                    ru = "СЕТЕВЫЕ АДРЕСА СОБЕСЕДНИКА (${allEndpoints.size})",
                                    en = "PEER NETWORK ENDPOINTS (${allEndpoints.size})",
                                    de = "PEER-NETZWERKADRESSEN (${allEndpoints.size})",
                                    es = "PUNTOS DE CONEXIÓN DEL PAR (${allEndpoints.size})",
                                    fr = "ADRESSES RÉSEAU DU PAIR (${allEndpoints.size})",
                                    pt = "ENDEREÇOS DE REDE DO PAR (${allEndpoints.size})"
                                ),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceVariant.copy(alpha = 0.70f),
                                letterSpacing = 0.6.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (allEndpoints.size > 3) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { isEndpointsExpanded = !isEndpointsExpanded }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isEndpointsExpanded) {
                                            Localizations.tr(appLanguage, ru = "Свернуть", en = "Collapse", de = "Einklappen", es = "Colapsar", fr = "Réduire", pt = "Recolher")
                                        } else {
                                            Localizations.tr(appLanguage, ru = "Все (${allEndpoints.size})", en = "All (${allEndpoints.size})", de = "Alle (${allEndpoints.size})", es = "Todos (${allEndpoints.size})", fr = "Tous (${allEndpoints.size})", pt = "Todos (${allEndpoints.size})")
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryColor
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        imageVector = if (isEndpointsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))

                        val displayedEndpoints = if (isEndpointsExpanded) allEndpoints else allEndpoints.take(3)
                        displayedEndpoints.forEach { ep ->
                            val isEpOnion = ep.contains(".onion", ignoreCase = true)
                            val isEpYgg = ep.startsWith("[") || (ep.contains(":") && ep.count { it == ':' } > 1)
                            val epTypeLabel = when {
                                isEpOnion -> "Tor .onion"
                                isEpYgg -> "Yggdrasil IPv6"
                                else -> "Direct IPv4 / LAN"
                            }
                            val epColor = if (isEpOnion) Color(0xFFA78BFA) else Color(0xFF10B981)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(onSurfaceColor.copy(alpha = 0.04f))
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(ep))
                                        Toast.makeText(
                                            context,
                                            Localizations.tr(
                                                language = appLanguage,
                                                ru = "Адрес скопирован ($epTypeLabel)",
                                                en = "Copied ($epTypeLabel)",
                                                de = "Kopiert ($epTypeLabel)",
                                                es = "Copiado ($epTypeLabel)",
                                                fr = "Copié ($epTypeLabel)",
                                                pt = "Copiado ($epTypeLabel)"
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(epColor)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = epTypeLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = epColor,
                                    modifier = Modifier.width(90.dp)
                                )
                                Text(
                                    text = ep,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = onSurfaceColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = Localizations.tr(appLanguage, ru = "копировать", en = "copy", de = "kopieren", es = "copiar", fr = "copier", pt = "copiar"),
                                    fontSize = 10.sp,
                                    color = primaryColor
                                )
                            }
                        }

                        if (!isEndpointsExpanded && allEndpoints.size > 3) {
                            Spacer(Modifier.height(2.dp))
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { isEndpointsExpanded = true }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    text = Localizations.tr(
                                        language = appLanguage,
                                        ru = "Показать ещё ${allEndpoints.size - 3} адреса...",
                                        en = "Show ${allEndpoints.size - 3} more endpoints...",
                                        de = "Noch ${allEndpoints.size - 3} Adressen anzeigen...",
                                        es = "Mostrar ${allEndpoints.size - 3} direcciones más...",
                                        fr = "Afficher ${allEndpoints.size - 3} adresses de plus...",
                                        pt = "Mostrar mais ${allEndpoints.size - 3} endereços..."
                                    ),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryColor
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(16.dp).padding(start = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (isRussian) "ВЫБЕРИТЕ КАНАЛ СВЯЗИ" else "SELECT TRANSPORT MODE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceVariant,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            // Mode 1: AUTO
            TransportOptionCard(
                title = if (isRussian) "⚡ Автоматически (Рекомендуется)" else "⚡ Automatic (Recommended)",
                badge = if (isRussian) "Умный выбор" else "Smart",
                badgeColor = Color(0xFF10B981),
                description = if (isRussian) {
                    "Приложение автоматически выбирает самый быстрый и надежный зашифрованный канал связи (Direct P2P, Yggdrasil или Tor)."
                } else {
                    "Automatically selects the fastest and most reliable encrypted route (Direct P2P, Yggdrasil, or Tor)."
                },
                selected = currentPreference == PeerTransportPreference.AUTO,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onClick = { selectMode(PeerTransportPreference.AUTO) }
            )

            Spacer(Modifier.height(10.dp))

            // Mode 2: TOR ONLY
            val hasOnion = activeEndpoint.contains(".onion", ignoreCase = true) ||
                    P2PPreferences.getPeerOnionAddress(context, peerName) != null
            TransportOptionCard(
                title = if (isRussian) "🟣 Только Tor (Максимальная анонимность)" else "🟣 Tor Only (Maximum Anonymity)",
                badge = if (isRussian) "IP скрыт" else "Private",
                badgeColor = Color(0xFFA78BFA),
                description = if (isRussian) {
                    "Весь трафик проходит строго через 3 узла Tor (.onion). Реальные IP-адреса обоих собеседников полностью скрыты от провайдеров." +
                            if (!hasOnion) "\n⚠️ Tor-адрес собеседника будет применен автоматически после первого обмена." else ""
                } else {
                    "All traffic is strictly routed through 3-hop Tor v3 (.onion). Real IP addresses of both peers are fully hidden from ISPs." +
                            if (!hasOnion) "\n⚠️ Peer .onion address will be used once shared." else ""
                },
                selected = currentPreference == PeerTransportPreference.TOR_ONLY,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onClick = { selectMode(PeerTransportPreference.TOR_ONLY) }
            )

            Spacer(Modifier.height(10.dp))

            // Mode 3: YGGDRASIL ONLY
            TransportOptionCard(
                title = if (isRussian) "🟢 Только Yggdrasil (Mesh-сеть)" else "🟢 Yggdrasil Only (Mesh Overlay)",
                badge = if (isRussian) "IPv6 Mesh" else "IPv6 Mesh",
                badgeColor = Color(0xFF10B981),
                description = if (isRussian) {
                    "Децентрализованная зашифрованная IPv6 mesh-сеть. Прямой провайдерский IP скрыт, низкая задержка и обход NAT."
                } else {
                    "Decentralized encrypted IPv6 mesh overlay. Hides ISP clearnet IP, low latency, and bypasses NAT."
                },
                selected = currentPreference == PeerTransportPreference.YGGDRASIL_ONLY,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onClick = { selectMode(PeerTransportPreference.YGGDRASIL_ONLY) }
            )

            Spacer(Modifier.height(10.dp))

            // Mode 4: DIRECT ONLY
            TransportOptionCard(
                title = if (isRussian) "🟢 Прямой P2P (Wi-Fi / Локальная сеть)" else "🟢 Direct P2P (Local / Direct IP)",
                badge = if (isRussian) "Быстрый пинг" else "Fastest",
                badgeColor = Color(0xFF10B981),
                description = if (isRussian) {
                    "Прямое соединение без промежуточных узлов для максимальной скорости (в локальной сети Wi-Fi или по прямому IP-адресу)."
                } else {
                    "Direct connection without intermediate hops for maximum speed (local Wi-Fi network or direct public IP)."
                },
                selected = currentPreference == PeerTransportPreference.DIRECT_ONLY,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onClick = { selectMode(PeerTransportPreference.DIRECT_ONLY) }
            )

            Spacer(Modifier.height(20.dp))

            // Quick Actions: Rotate Tor Circuit & Reconnect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val success = TorManager.renewTorIdentity(context)
                            Toast.makeText(
                                context,
                                if (success) {
                                    if (isRussian) "Цепочка Tor успешно обновлена (SIGNAL NEWNYM)" else "Tor circuit renewed (SIGNAL NEWNYM)"
                                } else {
                                    if (isRussian) "Служба Tor не запущена" else "Tor is not active"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onSurfaceColor),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    if (isRotatingCircuit) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = primaryColor
                        )
                    } else {
                        Text(
                            text = if (isRussian) "🔄 Сменить Tor" else "🔄 Rotate Tor",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            isReconnecting = true
                            P2PMessageRelay.reconnectSession(context, peerName) {
                                isReconnecting = false
                            }
                            Toast.makeText(
                                context,
                                if (isRussian) "Переподключение начато..." else "Reconnecting...",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor.copy(alpha = 0.15f),
                        contentColor = primaryColor
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    if (isReconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = primaryColor
                        )
                    } else {
                        Text(
                            text = if (isRussian) "⚡ Переподключить" else "⚡ Reconnect",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Done Button
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (isRussian) "Готово" else "Done",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun TransportOptionCard(
    title: String,
    badge: String,
    badgeColor: Color,
    description: String,
    selected: Boolean,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
        animationSpec = tween(200)
    )
    val containerBg by animateColorAsState(
        targetValue = if (selected) primaryColor.copy(alpha = 0.08f) else onSurfaceColor.copy(alpha = 0.02f),
        animationSpec = tween(200)
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerBg,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = primaryColor,
                    unselectedColor = onSurfaceVariant.copy(alpha = 0.40f)
                ),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = onSurfaceColor,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = onSurfaceVariant
                )
            }
        }
    }
}

private fun isValidPeerEndpoint(ep: String): Boolean {
    val trimmed = ep.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.contains(".onion", ignoreCase = true)) {
        return trimmed.matches(Regex("^[a-z2-7]{56}\\.onion(:\\d{1,5})?$", RegexOption.IGNORE_CASE))
    }
    if (trimmed.startsWith("[") || (trimmed.contains(":") && trimmed.count { it == ':' } > 1)) {
        return true
    }
    val host = if (trimmed.contains(':')) trimmed.substringBeforeLast(':') else trimmed
    val portStr = if (trimmed.contains(':')) trimmed.substringAfterLast(':') else ""
    val octets = host.split('.')
    if (octets.size != 4) return false
    val validOctets = octets.all { it.toIntOrNull() in 0..255 }
    val validPort = portStr.isEmpty() || (portStr.toIntOrNull() in 1..65535)
    return validOctets && validPort
}

