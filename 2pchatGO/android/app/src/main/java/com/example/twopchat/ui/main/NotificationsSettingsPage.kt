package com.example.twopchat.ui.main

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

@Composable
fun NotificationsSettingsPage(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }

    var notificationsEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_notifications", true))
    }
    var previewsEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_previews", true))
    }
    var hapticFeedbackEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_haptic_feedback", true))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubPageLayout(
            title = Localizations.tr(appLanguage, "Уведомления", "Notifications", "Benachrichtigungen", "Notificaciones", "Notifications", "Notificações", tr = "Bildirimler"),
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor
        ) {
            // Notifications Settings Card
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Master Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localizations.getString("push_notifications", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                            Text(Localizations.getString("push_notifications_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = {
                                notificationsEnabled = it
                                sharedPrefs.edit().putBoolean("settings_notifications", it).apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    // Previews Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localizations.getString("message_previews", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                            Text(Localizations.getString("message_previews_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = previewsEnabled,
                            onCheckedChange = {
                                previewsEnabled = it
                                sharedPrefs.edit().putBoolean("settings_previews", it).apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    // Haptic Feedback Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localizations.getString("haptic_feedback", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                            Text(Localizations.getString("haptic_feedback_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = hapticFeedbackEnabled,
                            onCheckedChange = {
                                hapticFeedbackEnabled = it
                                sharedPrefs.edit().putBoolean("settings_haptic_feedback", it).apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Background Delivery & Battery Optimization Card
            val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager }
            var isBatteryOptIgnored by remember {
                mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            "Фоновая доставка сообщений",
                            "Background Message Delivery",
                            "Hintergrund-Nachrichtenzustellung",
                            "Entrega de mensajes en segundo plano",
                            "Distribution des messages en arrière-plan",
                            "Entrega de mensagens em segundo plano",
                            tr = "Arka Planda Mesaj İletimi"
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = onSurfaceColor,
                        fontSize = 15.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            "Для мгновенного получения уведомлений при заблокированном телефоне рекомендуется разрешить работу без ограничений батареи.",
                            "To receive instant notifications while the phone is locked, allow unrestricted background battery usage.",
                            "Um Benachrichtigungen bei gesperrtem Telefon sofort zu erhalten, erlaube unbegrenzte Hintergrundaktivität.",
                            "Para recibir notificaciones al instante con el teléfono bloqueado, permite el uso de batería sin restricciones.",
                            "Pour recevoir des notifications instantanées lorsque le téléphone est verrouillé, autorisez l'utilisation sans restriction de la batterie.",
                            "Para receber notificações instantâneas com o telefone bloqueado, permita o uso irrestrito da bateria.",
                            tr = "Telefon kilitliyken anında bildirim almak için kısıtlamasız arka plan pil kullanımına izin verin."
                        ),
                        fontSize = 12.sp,
                        color = onSurfaceVariant,
                        lineHeight = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (isBatteryOptIgnored) {
                                "✓ " + Localizations.tr(
                                    appLanguage,
                                    "Работа без ограничений активна",
                                    "Unrestricted mode active",
                                    "Uneingeschränkter Modus aktiv",
                                    "Modo sin restricciones activo",
                                    "Mode sans restriction actif",
                                    "Modo irrestrito ativo",
                                    tr = "Kısıtlamasız mod aktif"
                                )
                            } else {
                                Localizations.tr(
                                    appLanguage,
                                    "Оптимизация включена (возможны задержки)",
                                    "Optimized (delivery may be delayed)",
                                    "Optimiert (Verzögerung möglich)",
                                    "Optimizado (puede haber retrasos)",
                                    "Optimisé (retards possibles)",
                                    "Otimizado (possíveis atrasos)",
                                    tr = "Optimize edildi (gecikmeler yaşanabilir)"
                                )
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isBatteryOptIgnored) primaryColor else onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (!isBatteryOptIgnored) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    runCatching {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    }.onFailure {
                                        runCatching {
                                            val fallback = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(fallback)
                                        }
                                    }
                                    isBatteryOptIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = Localizations.tr(
                                        appLanguage,
                                        "Разрешить",
                                        "Allow",
                                        "Erlauben",
                                        "Permitir",
                                        "Autoriser",
                                        "Permitir",
                                        tr = "İzin Ver"
                                    ),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
