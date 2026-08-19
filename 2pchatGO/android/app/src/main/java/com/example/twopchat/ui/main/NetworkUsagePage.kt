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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.NetworkTrafficSnapshot
import com.example.twopchat.NetworkTrafficStats
import com.example.twopchat.TrafficCategory
import com.example.twopchat.TrafficCounter
import com.example.twopchat.TrafficProtocol
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

@Composable
internal fun NetworkUsagePage(
    appLanguage: String,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(NetworkTrafficStats.snapshot(context)) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val isRussian = appLanguage == "Русский"

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = {
                Text(if (isRussian) "Сбросить статистику?" else "Reset statistics?")
            },
            text = {
                Text(
                    if (isRussian) {
                        "Счётчики входящего и исходящего трафика будут обнулены."
                    } else {
                        "All received and sent traffic counters will be reset."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        NetworkTrafficStats.reset(context)
                        snapshot = NetworkTrafficStats.snapshot(context)
                        showResetConfirmation = false
                    },
                ) {
                    Text(if (isRussian) "Сбросить" else "Reset", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(if (isRussian) "Отмена" else "Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageLayout(
            title = if (isRussian) "Использование сети" else "Network Usage",
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
        ) {
            TrafficSummaryCard(
                snapshot = snapshot,
                isRussian = isRussian,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                primaryColor = primaryColor,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = if (isRussian) "По протоколам" else "By protocol",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            val visibleProtocols = buildList {
                add(TrafficProtocol.DIRECT_P2P)
                add(TrafficProtocol.YGGDRASIL)
                add(TrafficProtocol.TOR_ONION)
                if (snapshot.byProtocol.getValue(TrafficProtocol.UNKNOWN).totalBytes > 0L) {
                    add(TrafficProtocol.UNKNOWN)
                }
            }
            visibleProtocols.forEachIndexed { index, protocol ->
                ProtocolTrafficCard(
                    protocol = protocol,
                    snapshot = snapshot,
                    isRussian = isRussian,
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    primaryColor = primaryColor,
                )
                if (index != visibleProtocols.lastIndex) Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = if (isRussian) {
                    "Учитывается полезная нагрузка 2PChat: сообщения, служебные пакеты и завершённые передачи файлов. Системный трафик туннеля и повторы внутри транспорта не включены."
                } else {
                    "2PChat payload is counted: messages, service packets, and completed file transfers. Tunnel system traffic and retries inside a transport are not included."
                },
                color = onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { showResetConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF5252).copy(alpha = 0.16f),
                    contentColor = Color(0xFFFF5252),
                ),
            ) {
                Text(
                    if (isRussian) "Сбросить статистику" else "Reset statistics",
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TrafficSummaryCard(
    snapshot: NetworkTrafficSnapshot,
    isRussian: Boolean,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
) {
    val total = snapshot.total
    TrafficCard(surfaceColor, onSurfaceColor) {
        Text(
            text = if (isRussian) "Всего передано" else "Total traffic",
            color = onSurfaceVariant,
            fontSize = 12.sp,
        )
        Text(
            text = formatTrafficSize(total.totalBytes),
            color = primaryColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
        Spacer(Modifier.height(10.dp))
        TrafficValueRow(
            label = if (isRussian) "Получено" else "Received",
            counter = total.receivedBytes,
            items = total.receivedItems,
            isRussian = isRussian,
            color = onSurfaceColor,
            valueColor = onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TrafficValueRow(
            label = if (isRussian) "Отправлено" else "Sent",
            counter = total.sentBytes,
            items = total.sentItems,
            isRussian = isRussian,
            color = onSurfaceColor,
            valueColor = onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = (if (isRussian) "С " else "Since ") + DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                if (isRussian) Locale("ru") else Locale.getDefault(),
            ).format(Date(snapshot.startedAtMs)),
            color = onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ProtocolTrafficCard(
    protocol: TrafficProtocol,
    snapshot: NetworkTrafficSnapshot,
    isRussian: Boolean,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
) {
    val total = snapshot.byProtocol.getValue(protocol)
    val categories = snapshot.details.getValue(protocol)
    TrafficCard(surfaceColor, onSurfaceColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                protocolLabel(protocol, isRussian),
                color = onSurfaceColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formatTrafficSize(total.totalBytes),
                color = primaryColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        TrafficValueRow(
            if (isRussian) "Получено" else "Received",
            total.receivedBytes,
            total.receivedItems,
            isRussian,
            onSurfaceColor,
            onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        TrafficValueRow(
            if (isRussian) "Отправлено" else "Sent",
            total.sentBytes,
            total.sentItems,
            isRussian,
            onSurfaceColor,
            onSurfaceVariant,
        )

        val nonEmptyCategories = TrafficCategory.entries.filter {
            categories.getValue(it).totalBytes > 0L || categories.getValue(it).totalItems > 0L
        }
        if (nonEmptyCategories.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.06f))
            Spacer(Modifier.height(10.dp))
            nonEmptyCategories.forEachIndexed { index, category ->
                CategoryRow(
                    category = category,
                    counter = categories.getValue(category),
                    isRussian = isRussian,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                )
                if (index != nonEmptyCategories.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: TrafficCategory,
    counter: TrafficCounter,
    isRussian: Boolean,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            categoryLabel(category, isRussian),
            color = onSurfaceColor,
            fontSize = 13.sp,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatTrafficSize(counter.totalBytes),
                color = onSurfaceColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "↓ ${formatTrafficSize(counter.receivedBytes)}  ↑ ${formatTrafficSize(counter.sentBytes)}",
                color = onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun TrafficValueRow(
    label: String,
    counter: Long,
    items: Long,
    isRussian: Boolean,
    color: Color,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontSize = 13.sp)
        Text(
            "${formatTrafficSize(counter)} • $items ${itemLabel(items, isRussian)}",
            color = valueColor,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TrafficCard(
    surfaceColor: Color,
    onSurfaceColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

internal fun formatTrafficSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return if (group == 0) {
        "$bytes B"
    } else {
        String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(group), units[group])
    }
}

private fun protocolLabel(protocol: TrafficProtocol, isRussian: Boolean): String = when (protocol) {
    TrafficProtocol.DIRECT_P2P -> "Direct P2P"
    TrafficProtocol.YGGDRASIL -> "Yggdrasil P2P"
    TrafficProtocol.TOR_ONION -> "Tor Onion"
    TrafficProtocol.UNKNOWN -> if (isRussian) "Неизвестный маршрут" else "Unknown route"
}

private fun categoryLabel(category: TrafficCategory, isRussian: Boolean): String =
    if (isRussian) {
        when (category) {
            TrafficCategory.MESSAGES -> "Сообщения"
            TrafficCategory.PHOTOS -> "Фотографии"
            TrafficCategory.VIDEOS -> "Видео"
            TrafficCategory.GIFS -> "GIF"
            TrafficCategory.STICKERS -> "Стикеры"
            TrafficCategory.VOICE -> "Голосовые сообщения"
            TrafficCategory.FILES -> "Файлы"
            TrafficCategory.SERVICE -> "Служебные данные"
        }
    } else {
        when (category) {
            TrafficCategory.MESSAGES -> "Messages"
            TrafficCategory.PHOTOS -> "Photos"
            TrafficCategory.VIDEOS -> "Videos"
            TrafficCategory.GIFS -> "GIFs"
            TrafficCategory.STICKERS -> "Stickers"
            TrafficCategory.VOICE -> "Voice messages"
            TrafficCategory.FILES -> "Files"
            TrafficCategory.SERVICE -> "Service data"
        }
    }

private fun itemLabel(items: Long, isRussian: Boolean): String =
    if (!isRussian) {
        if (items == 1L) "item" else "items"
    } else {
        val mod100 = items % 100
        val mod10 = items % 10
        when {
            mod100 in 11L..14L -> "элементов"
            mod10 == 1L -> "элемент"
            mod10 in 2L..4L -> "элемента"
            else -> "элементов"
        }
    }
