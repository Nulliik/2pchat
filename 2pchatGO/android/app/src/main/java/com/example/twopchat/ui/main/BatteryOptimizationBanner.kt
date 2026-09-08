package com.example.twopchat.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations

@Composable
fun BatteryOptimizationBanner(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    var isIgnored by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }
    var isDismissed by remember {
        mutableStateOf(P2PPreferences.isBatteryOptBannerDismissed(context))
    }

    // Refresh state when returning to UI
    DisposableEffect(Unit) {
        isIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        onDispose {}
    }

    val visible = !isIgnored && !isDismissed
    val isDark = surfaceColor.luminance() < 0.5f

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .border(
                    width = 1.dp,
                    color = primaryColor.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "⚡",
                            fontSize = 18.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = Localizations.tr(
                                appLanguage,
                                ru = "Фоновая доставка сообщений",
                                en = "Background Message Delivery",
                                de = "Hintergrund-Nachrichtenzustellung",
                                es = "Entrega de mensajes en segundo plano",
                                fr = "Distribution des messages en arrière-plan",
                                pt = "Entrega de mensagens em segundo plano",
                                tr = "Arka Planda Mesaj İletimi"
                            ),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else Color.Black,
                        )
                    }

                    IconButton(
                        onClick = {
                            isDismissed = true
                            P2PPreferences.setBatteryOptBannerDismissed(context, true)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text(
                            text = "✕",
                            fontSize = 14.sp,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = Localizations.tr(
                        appLanguage,
                        ru = "Включите работу в фоне, чтобы мгновенно получать сообщения и обновлять группы при заблокированном экране.",
                        en = "Allow background activity to receive messages and group updates instantly while the screen is locked.",
                        de = "Hintergrundaktivität erlauben, um Nachrichten und Gruppenupdates bei gesperrtem Bildschirm sofort zu empfangen.",
                        es = "Permita la actividad en segundo plano para recibir mensajes y actualizaciones de grupo al instante con la pantalla bloqueada.",
                        fr = "Autorisez l'activité en arrière-plan pour recevoir instantanément les messages et mises à jour de groupe écran verrouillé.",
                        pt = "Permita a atividade em segundo plano para receber mensagens e atualizações de grupo instantaneamente com a tela bloqueada.",
                        tr = "Ekran kilitliyken mesajları ve grup güncellemelerini anında almak için arka plan etkinliğine izin verin."
                    ),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            isDismissed = true
                            P2PPreferences.setBatteryOptBannerDismissed(context, true)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = Localizations.tr(
                                appLanguage,
                                ru = "Позже",
                                en = "Later",
                                de = "Später",
                                es = "Más tarde",
                                fr = "Plus tard",
                                pt = "Mais tarde",
                                tr = "Daha sonra"
                            ),
                            fontSize = 12.sp,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(fallback)
                                }
                            }
                            isIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = Localizations.tr(
                                appLanguage,
                                ru = "Разрешить",
                                en = "Allow",
                                de = "Erlauben",
                                es = "Permitir",
                                fr = "Autoriser",
                                pt = "Permitir",
                                tr = "İzin Ver"
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
