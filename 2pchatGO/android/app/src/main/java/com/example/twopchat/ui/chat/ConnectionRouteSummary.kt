package com.example.twopchat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.data.Localizations
import com.example.twopchat.relay.EndpointKind
import com.example.twopchat.relay.EndpointRecord
import com.example.twopchat.relay.EndpointRetention
import com.example.twopchat.relay.EndpointSource
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.relay.PeerEndpointStore
import com.example.twopchat.relay.canonicalEndpointFingerprint
import com.example.twopchat.relay.connectionTransportLabel
import com.example.twopchat.relay.legacyUnpinnedReconnectCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConnectionRouteSummary(
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val fingerprint = P2PPreferences.getPeerFingerprint(context, peerName) ?: canonicalEndpointFingerprint(peerName)
    val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
    val activeEndpoint = P2PMessageRelay.peerEndpoints[peerName].orEmpty()
    val activeTransport = P2PMessageRelay.peerConnectionTransports[peerName]
    val rttMs = P2PMessageRelay.peerRttMs[peerName]
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var connectingEndpoint by remember { mutableStateOf<String?>(null) }
    var reloadVersion by remember(peerName) { mutableIntStateOf(0) }
    var history by remember(peerName) { mutableStateOf<List<EndpointRecord>>(emptyList()) }
    var historyLoaded by remember(peerName) { mutableStateOf(false) }
    var showFullHistory by remember(peerName) { mutableStateOf(false) }

    fun copyEndpointToClipboard(endpoint: String) {
        copyTextToClipboard(context, "Peer Endpoint", endpoint)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(
            context,
            Localizations.tr(appLanguage, "Адрес скопирован в буфер", "Endpoint copied to clipboard"),
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(peerName, fingerprint, reloadVersion) {
        historyLoaded = false
        history = withContext(Dispatchers.IO) {
            val dbHistory = fingerprint?.let { PeerEndpointStore.history(context, peerName, it) }.orEmpty()
            if (dbHistory.isNotEmpty()) {
                dbHistory
            } else {
                val legacyEps = legacyUnpinnedReconnectCandidates(
                    fingerprint,
                    P2PPreferences.getEffectiveEndpointsForPeer(context, peerName),
                )
                legacyEps.map { ep ->
                    EndpointRecord(
                        fingerprint = fingerprint.orEmpty(),
                        endpoint = ep,
                        source = EndpointSource.MIGRATED,
                        firstSeen = 0L,
                        lastSeen = 0L,
                        savedContact = true,
                    )
                }
            }
        }
        historyLoaded = true
    }

    val routeLabel = connectionTransportLabel(activeTransport, activeEndpoint, appLanguage)
    val torInboundLoopback = routeLabel == "Tor Onion" && isTorLoopbackSocket(activeEndpoint)
    val onlineLabel = Localizations.tr(
        appLanguage,
        ru = if (isOnline) "Подключено" else "Нет активного соединения",
        en = if (isOnline) "Connected" else "No active connection",
    )
    val activeColor = if (isOnline) Color(0xFF35B86B) else onSurfaceVariant

    val activeDetail = if (isOnline && activeEndpoint.isNotBlank()) {
        val base = if (torInboundLoopback) {
            Localizations.tr(
                appLanguage,
                "Tor Onion · входящее соединение через локальный Tor-сервис",
                "Tor Onion · incoming through the local Tor service",
            )
        } else routeLabel
        if (rttMs != null && rttMs > 0) {
            "$base · RTT: ${rttMs} ms"
        } else {
            base
        }
    } else {
        Localizations.tr(
            appLanguage,
            "Соединение не установлено",
            "A connection has not been established",
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .border(0.5.dp, primaryColor.copy(alpha = 0.20f), RoundedCornerShape(20.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Localizations.tr(appLanguage, "Подключение", "Connection"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                    )
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            "Текущий маршрут и результаты проверок адресов",
                            "Current route and endpoint check results",
                        ),
                        fontSize = 11.sp,
                        color = onSurfaceVariant,
                    )
                }
                Text(
                    text = if (isRefreshing) "…" else Localizations.tr(appLanguage, "Обновить", "Refresh"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isRefreshing) {
                            coroutineScope.launch {
                                isRefreshing = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        PeerEndpointStore.resetCooldowns(context, fingerprint ?: peerName)
                                    }
                                    P2PMessageRelay.resetPeerBackoffs(peerName)
                                    fingerprint?.let { P2PMessageRelay.resetPeerBackoffs(it) }
                                    val candidates = withContext(Dispatchers.IO) {
                                        fingerprint?.let { PeerEndpointStore.candidates(context, peerName, it, includeReserve = true) }.orEmpty()
                                    }
                                    val target = candidates.firstOrNull() ?: P2PPreferences.getEffectiveEndpointsForPeer(context, peerName)
                                    if (target.isNotBlank()) {
                                        com.example.twopchat.bridge.P2PBridgeProvider.get(context)
                                            .reconnectPeerSession(peerName, target, fingerprint)
                                    }
                                    P2PMessageRelay.triggerImmediateReconnect(context).join()
                                    kotlinx.coroutines.delay(1000L)
                                } finally {
                                    reloadVersion++
                                    isRefreshing = false
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            RouteStatusRow(
                dotColor = activeColor,
                title = onlineLabel,
                detail = activeDetail,
                endpoint = activeEndpoint.takeIf { isOnline && it.isNotBlank() && !torInboundLoopback },
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                primaryColor = primaryColor,
                onLongClick = activeEndpoint.takeIf { it.isNotBlank() }?.let { ep -> { copyEndpointToClipboard(ep) } },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = Localizations.tr(appLanguage, "Адреса подключения", "Connection endpoints"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                !historyLoaded -> Text(
                    text = Localizations.tr(appLanguage, "Загружаем историю адресов…", "Loading endpoint history…"),
                    fontSize = 12.sp,
                    color = onSurfaceVariant,
                )
                history.isEmpty() -> Text(
                    text = Localizations.tr(
                        appLanguage,
                        "Адреса для этого контакта ещё не получены.",
                        "No endpoints have been received for this contact yet.",
                    ),
                    fontSize = 12.sp,
                    color = onSurfaceVariant,
                )
                else -> {
                    val displayedHistory = if (showFullHistory) history else history.take(3)
                    displayedHistory.forEachIndexed { index, record ->
                        val isConnectingThis = connectingEndpoint == record.endpoint
                        EndpointHistoryRow(
                            record = record,
                            activeEndpoint = activeEndpoint,
                            isOnline = isOnline,
                            isConnecting = isConnectingThis,
                            appLanguage = appLanguage,
                            primaryColor = primaryColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceVariant = onSurfaceVariant,
                            onClick = {
                                coroutineScope.launch {
                                    connectingEndpoint = record.endpoint
                                    try {
                                        withContext(Dispatchers.IO) {
                                            fingerprint?.let { PeerEndpointStore.resetCooldowns(context, it) }
                                        }
                                        P2PMessageRelay.resetPeerBackoffs(peerName)
                                        fingerprint?.let { P2PMessageRelay.resetPeerBackoffs(it) }
                                        com.example.twopchat.bridge.P2PBridgeProvider.get(context)
                                            .reconnectPeerSession(peerName, record.endpoint, fingerprint)
                                        kotlinx.coroutines.delay(1200L)
                                    } finally {
                                        reloadVersion++
                                        connectingEndpoint = null
                                    }
                                }
                            },
                            onLongClick = { copyEndpointToClipboard(record.endpoint) },
                        )
                        if (index != displayedHistory.lastIndex) Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (history.size > 3) {
                        Text(
                            text = if (showFullHistory) {
                                Localizations.tr(appLanguage, "Свернуть", "Show less")
                            } else {
                                Localizations.tr(appLanguage, "Все адреса (${history.size})", "All endpoints (${history.size})")
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showFullHistory = !showFullHistory }
                                .padding(top = 12.dp, bottom = 4.dp, end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointHistoryRow(
    record: EndpointRecord,
    activeEndpoint: String,
    isOnline: Boolean,
    isConnecting: Boolean,
    appLanguage: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    val active = isOnline && record.endpoint == activeEndpoint
    val inCooldown = record.retryAfter > now

    val (color, state) = when {
        active -> Color(0xFF35B86B) to Localizations.tr(appLanguage, "Используется сейчас", "In use now")
        isConnecting -> primaryColor to Localizations.tr(appLanguage, "Подключение…", "Connecting…")
        inCooldown -> Color(0xFFE26D6D) to Localizations.tr(
            appLanguage,
            "Пауза до ${connectionRouteTime(record.retryAfter)} · Нажмите для сброса",
            "Cooldown until ${connectionRouteTime(record.retryAfter)} · Tap to retry",
        )
        record.lastSuccess > 0 && record.lastSuccess >= record.lastFailure ->
            Color(0xFF4DB6AC) to Localizations.tr(appLanguage, "Ранее успешно проверен", "Previously succeeded")
        record.lastFailure > 0 -> Color(0xFFE26D6D) to Localizations.tr(appLanguage, "Подключиться не удалось", "Connection failed")
        else -> Color(0xFF6CA7E8) to Localizations.tr(appLanguage, "Ещё не проверен", "Not checked yet")
    }
    val activityAt = maxOf(record.lastSuccess, record.lastFailure, record.lastSeen)
    val source = when (record.source) {
        EndpointSource.AUTHENTICATED -> Localizations.tr(appLanguage, "получен от контакта", "received from contact")
        EndpointSource.DISCOVERY -> Localizations.tr(appLanguage, "найден через поиск", "found by discovery")
        EndpointSource.MANUAL -> Localizations.tr(appLanguage, "добавлен вручную", "added manually")
        EndpointSource.MIGRATED -> if (record.fingerprint.isEmpty()) {
            Localizations.tr(appLanguage, "сохранён ранее (bootstrap)", "saved previously (bootstrap)")
        } else {
            Localizations.tr(appLanguage, "сохранён ранее", "saved previously")
        }
    }
    val detail = buildString {
        append(endpointNetworkLabel(record.endpoint, appLanguage))
        append(" · ")
        append(source)
        if (activityAt > 0) append(" · ").append(connectionRouteTime(activityAt))
        if (record.failures > 1) append(" · ").append(record.failures).append("×")
    }
    RouteStatusRow(
        dotColor = color,
        title = state,
        detail = detail,
        endpoint = record.endpoint,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        primaryColor = primaryColor,
        isConnecting = isConnecting,
        canConnect = !active,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteStatusRow(
    dotColor: Color,
    title: String,
    detail: String,
    endpoint: String?,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
    isConnecting: Boolean = false,
    canConnect: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = { onLongClick?.invoke() },
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 5.dp)
                .size(12.dp),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    strokeWidth = 1.8.dp,
                    color = primaryColor,
                    modifier = Modifier.size(10.dp),
                )
            } else {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurfaceColor,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (canConnect && !isConnecting) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "›",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
            Text(detail, fontSize = 11.sp, color = onSurfaceVariant)
            endpoint?.let { ep ->
                Text(
                    text = ep,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = onSurfaceColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun connectionRouteTime(value: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(value))

private fun isTorLoopbackSocket(endpoint: String): Boolean {
    val value = endpoint.trim().lowercase()
    return value.startsWith("127.0.0.1:") || value.startsWith("[::1]:")
}

private fun endpointNetworkLabel(endpoint: String, appLanguage: String): String {
    val isIpv6 = endpoint.substringBeforeLast(':').contains(':')
    return when (EndpointRetention.kind(endpoint)) {
        EndpointKind.YGGDRASIL -> "Yggdrasil IPv6"
        EndpointKind.TOR -> "Tor Onion"
        EndpointKind.LAN -> Localizations.tr(appLanguage, if (isIpv6) "Локальная IPv6" else "Локальная IPv4", if (isIpv6) "Local IPv6" else "Local IPv4")
        EndpointKind.PUBLIC -> Localizations.tr(appLanguage, if (isIpv6) "Direct IPv6" else "Direct IPv4", if (isIpv6) "Direct IPv6" else "Direct IPv4")
    }
}
