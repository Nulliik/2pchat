package com.example.twopchat.ui.main

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.service.P2PRelayService

@Composable
fun AdvancedNetworkSettingsPage(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }

    var wifiDiscovery by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_wifi", true))
    }
    var ipv4Routing by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_ipv4", true))
    }
    var upnpEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean(P2PPreferences.UPNP_ENABLED, false))
    }
    var listenerPortText by remember {
        mutableStateOf(P2PPreferences.listenerPort(context).toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubPageLayout(
            title = Localizations.tr(appLanguage, "Параметры сети", "Network Parameters", "Netzwerkparameter", "Parámetros de red", "Paramètres réseau", "Parâmetros de rede"),
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Direct WiFi discovery
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localizations.getString("wifi_discovery", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                            Text(Localizations.getString("wifi_discovery_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = wifiDiscovery,
                            onCheckedChange = {
                                wifiDiscovery = it
                                sharedPrefs.edit().putBoolean("settings_wifi", it).apply()
                                P2PMessageRelay.setLocalDiscoveryEnabled(context, it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    val parsedListenerPort = listenerPortText.toIntOrNull()
                    val listenerPortValid = parsedListenerPort in
                        P2PPreferences.MIN_LISTENER_PORT..P2PPreferences.MAX_LISTENER_PORT
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (appLanguage == "Русский") "Порт входящих подключений" else "Incoming listener port",
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceColor,
                        )
                        Text(
                            if (appLanguage == "Русский") {
                                "Одинаковый порт не требуется: он публикуется через DHT, трекеры и локальную сеть"
                            } else {
                                "Peers learn this port through DHT, trackers, and local discovery"
                            },
                            fontSize = 12.sp,
                            color = onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = listenerPortText,
                                onValueChange = { value ->
                                    listenerPortText = value.filter(Char::isDigit).take(5)
                                },
                                keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                                    context = context,
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                                ),
                                singleLine = true,
                                isError = listenerPortText.isNotEmpty() && !listenerPortValid,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                enabled = listenerPortValid &&
                                    parsedListenerPort != P2PPreferences.listenerPort(context),
                                onClick = {
                                    val port = parsedListenerPort ?: return@TextButton
                                    sharedPrefs.edit().putInt(P2PPreferences.LISTENER_PORT, port).apply()
                                    androidx.core.content.ContextCompat.startForegroundService(
                                        context,
                                        Intent(context, P2PRelayService::class.java).apply {
                                            action = P2PRelayService.ACTION_RESTART
                                        },
                                    )
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "P2P-порт изменён на $port" else "P2P port changed to $port",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(if (appLanguage == "Русский") "Применить" else "Apply")
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    // Experimental Go Core status badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (appLanguage == "Русский") "Движок P2P" else "P2P Engine",
                                fontWeight = FontWeight.Medium,
                                color = onSurfaceColor
                            )
                            Text(
                                if (appLanguage == "Русский") {
                                    "Нативное Go-ядро (lib2pcore.so) — максимальная производительность, 0% GIL."
                                } else {
                                    "Native Go core (lib2pcore.so) — peak performance, 0% GIL."
                                },
                                fontSize = 12.sp,
                                color = onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = "Go Active",
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    // IPv4 transport
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (appLanguage == "Русский") "Подключение по IPv4" else "IPv4 connections",
                                fontWeight = FontWeight.Medium,
                                color = onSurfaceColor
                            )
                            Text(
                                if (appLanguage == "Русский") {
                                    "Анонсировать и использовать прямые IPv4-подключения"
                                } else {
                                    "Announce and use direct IPv4 connections"
                                },
                                fontSize = 12.sp,
                                color = onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = ipv4Routing,
                            onCheckedChange = { enabled ->
                                ipv4Routing = enabled
                                sharedPrefs.edit().putBoolean("settings_ipv4", enabled).apply()
                                P2PMessageRelay.refreshAnnouncement(context)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = primaryColor,
                                checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                            )
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    // UPnP Port Mapping
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (appLanguage == "Русский") "Проброс портов UPnP (Авто-NAT)" else "UPnP Port Mapping",
                                fontWeight = FontWeight.Medium,
                                color = onSurfaceColor
                            )
                            Text(
                                if (appLanguage == "Русский") {
                                    "Автоматический проброс порта на роутере. По умолчанию выключен для повышенной безопасности."
                                } else {
                                    "Automatically map port on router. Disabled by default for enhanced security."
                                },
                                fontSize = 12.sp,
                                color = onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = upnpEnabled,
                            onCheckedChange = { enabled ->
                                upnpEnabled = enabled
                                sharedPrefs.edit().putBoolean(P2PPreferences.UPNP_ENABLED, enabled).apply()
                                if (enabled) {
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "UPnP включён. Выполняется проброс порта..." else "UPnP enabled. Attempting port mapping...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "UPnP выключён." else "UPnP disabled.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                P2PMessageRelay.refreshAnnouncement(context)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = primaryColor,
                                checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
