package com.example.twopchat.ui.chat

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.tor.TorManager
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ChatProcessingAlbumDialog(appLanguage: String) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = if (appLanguage == "Русский") "Подготовка медиафайлов..." else "Preparing media files...",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDatePickerDialog(
    selectedDateFilterMs: Long?,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDateFilterMs ?: System.currentTimeMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { dateMs ->
                        val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMs }
                        val localCal = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.YEAR, utcCal.get(java.util.Calendar.YEAR))
                            set(java.util.Calendar.MONTH, utcCal.get(java.util.Calendar.MONTH))
                            set(java.util.Calendar.DAY_OF_MONTH, utcCal.get(java.util.Calendar.DAY_OF_MONTH))
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        onDateSelected(localCal.timeInMillis)
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
            ) {
                Text(
                    text = if (appLanguage == "Русский") "ОК" else "OK",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
            ) {
                Text(
                    text = if (appLanguage == "Русский") "ОТМЕНА" else "CANCEL",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = surfaceColor,
        )
    ) {
        androidx.compose.material3.DatePicker(
            state = datePickerState,
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = surfaceColor,
                titleContentColor = primaryColor,
                headlineContentColor = primaryColor,
                weekdayContentColor = onSurfaceColor.copy(alpha = 0.6f),
                subheadContentColor = onSurfaceColor,
                yearContentColor = onSurfaceColor,
                selectedYearContainerColor = primaryColor,
                selectedYearContentColor = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else Color.White,
                selectedDayContainerColor = primaryColor,
                selectedDayContentColor = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else Color.White,
                todayDateBorderColor = primaryColor,
                todayContentColor = primaryColor,
                dayContentColor = onSurfaceColor,
            )
        )
    }
}

@Composable
fun ChatVerifyPeerDialog(
    context: Context,
    peerName: String,
    localFingerprint: String?,
    activeFingerprint: String?,
    isVerified: Boolean,
    isWaitingForVerifyResponse: Boolean,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onSetVerified: (Boolean) -> Unit,
    onSetWaitingResponse: (Boolean) -> Unit,
) {
    val localFp = localFingerprint.orEmpty()
    val activeFp = activeFingerprint.orEmpty()
    val emojis = remember(localFp, activeFp) {
        getVerificationEmojis(localFp, activeFp)
    }

    LaunchedEffect(isWaitingForVerifyResponse) {
        if (isWaitingForVerifyResponse) {
            kotlinx.coroutines.delay(30000L)
            if (isWaitingForVerifyResponse) {
                onSetWaitingResponse(false)
                Toast.makeText(
                    context,
                    if (appLanguage == "Русский") "Собеседник не ответил на запрос верификации" else "Peer did not respond to verification request",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Localizations.getString("close", appLanguage), color = primaryColor)
            }
        },
        title = {
            Text(
                text = Localizations.getString("verify_peer", appLanguage),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (appLanguage == "Русский") {
                        "Сравните эти эмодзи безопасности со своим собеседником по другому каналу или голосом:"
                    } else {
                        "Compare these security emojis with your peer over another channel or voice:"
                    },
                    fontSize = 13.sp,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    emojis.forEach { emoji ->
                        Text(text = emoji, fontSize = 32.sp)
                    }
                }

                if (isVerified) {
                    val dangerRed = Color(0xFFE53935)
                    Button(
                        onClick = {
                            onSetVerified(false)
                            P2PPreferences.setPeerVerified(context, peerName, false)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dangerRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = Localizations.getString("unverify_btn", appLanguage),
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    if (isWaitingForVerifyResponse) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                color = primaryColor,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (appLanguage == "Русский") "Ожидание подтверждения от собеседника..." else "Waiting for confirmation from peer...",
                                fontSize = 11.sp,
                                color = onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { onSetWaitingResponse(false) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Отменить запрос" else "Cancel request",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                onSetWaitingResponse(true)
                                P2PMessageRelay.sendVerificationRequest(context, peerName) { success ->
                                    if (!success) {
                                        onSetWaitingResponse(false)
                                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось отправить запрос" else "Failed to send request", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (appLanguage == "Русский") "Отправить запрос верификации" else "Send verification request"
                            )
                        }
                    }
                }
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun ChatHardBlockDialog(
    context: Context,
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
    onBlocked: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (appLanguage == "Русский") "Блокировка и приватность" else "Block & Privacy",
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
            )
        },
        text = {
            Text(
                if (appLanguage == "Русский") {
                    "Этот пользователь знает ваш текущий Tor Onion-адрес. Сменить Tor-адрес сейчас (Hard Block), чтобы навсегда исключить возможность контакта?"
                } else {
                    "This user knows your current Tor .onion address. Rotate Onion address now (Hard Block) to permanently prevent further contact?"
                },
                fontSize = 13.sp,
                color = onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context.getSharedPreferences("twopchat_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("blocked_peer_$peerName", true).apply()
                    onBlocked()
                    onDismiss()
                    Toast.makeText(context, if (appLanguage == "Русский") "Пользователь заблокирован. Смена Tor-адреса..." else "User blocked. Rotating Tor address...", Toast.LENGTH_SHORT).show()
                    coroutineScope.launch {
                        TorManager.rotateOnionAddress(context)
                    }
                }
            ) {
                Text(
                    if (appLanguage == "Русский") "Сменить Tor и заблокировать" else "Rotate & Block",
                    color = primaryColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        context.getSharedPreferences("twopchat_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("blocked_peer_$peerName", true).apply()
                        onBlocked()
                        onDismiss()
                        Toast.makeText(context, if (appLanguage == "Русский") "Пользователь заблокирован" else "User blocked", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(if (appLanguage == "Русский") "Только заблокировать" else "Block Only", color = Color.Red)
                }
                TextButton(onClick = onDismiss) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel", color = onSurfaceVariant)
                }
            }
        },
        containerColor = surfaceColor,
    )
}

@Composable
fun ChatIncomingVerifyDialog(
    context: Context,
    peerName: String,
    localFingerprint: String?,
    peerFingerprint: String?,
    appLanguage: String,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val localFp = localFingerprint.orEmpty()
    val peerFp = peerFingerprint.orEmpty()
    val emojis = remember(localFp, peerFp) {
        getVerificationEmojis(localFp, peerFp)
    }

    AlertDialog(
        onDismissRequest = {
            onDismiss()
            P2PMessageRelay.sendVerificationResponse(context, peerName, false)
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    P2PMessageRelay.sendVerificationResponse(context, peerName, false)
                }
            ) {
                Text(if (appLanguage == "Русский") "Отклонить" else "Decline", color = MaterialTheme.colorScheme.error)
            }
        },
        title = {
            Text(
                text = if (appLanguage == "Русский") "Запрос верификации" else "Verification Request",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (appLanguage == "Русский") {
                        "$peerName предлагает подтвердить безопасность вашего подключения. Сверьте эти эмодзи:"
                    } else {
                        "$peerName wants to verify the security of your connection. Compare these emojis:"
                    },
                    fontSize = 13.sp,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    emojis.forEach { emoji ->
                        Text(text = emoji, fontSize = 32.sp)
                    }
                }

                Button(
                    onClick = {
                        P2PPreferences.setPeerVerified(context, peerName, true)
                        P2PMessageRelay.sendVerificationResponse(context, peerName, true)
                        onVerified()
                        onDismiss()
                        Toast.makeText(context, if (appLanguage == "Русский") "Личность подтверждена! Соединение защищено." else "Identity verified! Connection secured.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Подтвердить совпадение" else "Confirm Match"
                    )
                }
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun ChatIdentityWarningDialog(
    peerName: String,
    pendingFingerprint: String?,
    appLanguage: String,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onDismiss: () -> Unit,
    onReviewNewKey: () -> Unit,
) {
    val pendingFp = pendingFingerprint.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (appLanguage == "Русский") "Ключ безопасности изменился" else "Security key changed",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                if (appLanguage == "Русский") {
                    "У $peerName появился новый ключ. Это может быть переустановка приложения, новый аккаунт с тем же именем или попытка перехвата. До вашего решения соединение, сообщения, файлы и служебные подтверждения заблокированы."
                } else {
                    "$peerName presented a new key. This may be an app reinstall, a new account with the same name, or an interception attempt. Connection, messages, files, and delivery controls are blocked until you decide."
                },
                color = onSurfaceColor,
            )
        },
        confirmButton = {
            Button(
                onClick = onReviewNewKey,
                enabled = pendingFp.isNotBlank(),
            ) {
                Text(if (appLanguage == "Русский") "Проверить новый ключ" else "Review new key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (appLanguage == "Русский") "Оставить заблокированным" else "Keep blocked")
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
fun ChatIdentityConfirmationDialog(
    context: Context,
    peerName: String,
    activeFingerprint: String?,
    pendingFingerprint: String?,
    identityDecisionInProgress: Boolean,
    appLanguage: String,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onSetDecisionInProgress: (Boolean) -> Unit,
    onAcceptComplete: () -> Unit,
    onRejectComplete: () -> Unit,
) {
    val activeFp = activeFingerprint.orEmpty()
    val pendingFp = pendingFingerprint.orEmpty()
    AlertDialog(
        onDismissRequest = {
            if (!identityDecisionInProgress) {
                onDismiss()
            }
        },
        title = {
            Text(
                if (appLanguage == "Русский") "Подтвердить новый ключ?" else "Confirm the new key?",
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (appLanguage == "Русский") {
                        "Сверьте новый ключ с $peerName по другому доверенному каналу. После принятия прежняя верификация будет сброшена и создастся новая защищённая сессия."
                    } else {
                        "Compare the new key with $peerName over another trusted channel. Accepting it resets the previous verification and creates a new secure session."
                    },
                    color = onSurfaceColor,
                )
                Text(
                    (if (appLanguage == "Русский") "Прежний: " else "Previous: ") +
                        activeFp.chunked(4).joinToString(" "),
                    color = onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Text(
                    (if (appLanguage == "Русский") "Новый: " else "New: ") +
                        pendingFp.chunked(4).joinToString(" "),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (identityDecisionInProgress) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !identityDecisionInProgress && pendingFp.isNotBlank(),
                onClick = {
                    onSetDecisionInProgress(true)
                    P2PMessageRelay.acceptPendingPeerIdentity(context, peerName) { connected ->
                        onSetDecisionInProgress(false)
                        val accepted = !P2PPreferences.isPeerIdentityChangePending(context, peerName)
                        if (accepted) {
                            onAcceptComplete()
                        }
                        val message = if (!accepted) {
                            if (appLanguage == "Русский") "Не удалось принять новый ключ" else "Could not accept the new key"
                        } else if (appLanguage == "Русский") {
                            if (connected) "Новый ключ принят, создаётся новая сессия" else "Новый ключ принят; подключиться сейчас не удалось"
                        } else {
                            if (connected) "New key accepted; creating a new session" else "New key accepted; could not reconnect now"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
            ) {
                Text(if (appLanguage == "Русский") "Я сверил(а), принять" else "I verified it, accept")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !identityDecisionInProgress,
                onClick = {
                    onSetDecisionInProgress(true)
                    P2PMessageRelay.rejectPendingPeerIdentity(context, peerName) {
                        onSetDecisionInProgress(false)
                        onRejectComplete()
                        Toast.makeText(
                            context,
                            if (appLanguage == "Русский") "Новый ключ отклонён; сохранён прежний ключ" else "New key rejected; previous key remains pinned",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) {
                Text(if (appLanguage == "Русский") "Отклонить новый ключ" else "Reject new key")
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
fun ChatConnectionErrorDialog(
    context: Context,
    errorReasonYggdrasilDisabled: Boolean,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    vpnLauncher: ActivityResultLauncher<Intent>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Localizations.getString("close", appLanguage), color = primaryColor)
            }
        },
        title = {
            Text(
                text = if (appLanguage == "Русский") "Ошибка подключения" else "Connection Failed",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = if (errorReasonYggdrasilDisabled) {
                        if (appLanguage == "Русский") {
                            "Не удалось установить P2P-подключение к собеседнику. Скорее всего, вы или ваш собеседник находитесь за «серым» IP-адресом (NAT), что блокирует прямое соединение.\n\nРекомендуется включить Yggdrasil для обхода NAT и прямой связи."
                        } else {
                            "Failed to establish P2P connection. Most likely, you or your peer are behind a NAT/firewall which blocks direct packets.\n\nIt is recommended to enable Yggdrasil routing to bypass NAT."
                        }
                    } else {
                        if (appLanguage == "Русский") {
                            "Не удалось подключиться через сеть Yggdrasil. Убедитесь, что ваш собеседник также включил Yggdrasil в настройках и мессенджер запущен на обоих устройствах."
                        } else {
                            "Failed to connect via Yggdrasil. Ensure your peer has also enabled Yggdrasil and the app is active on both devices."
                        }
                    },
                    fontSize = 13.sp,
                    color = onSurfaceVariant
                )

                if (errorReasonYggdrasilDisabled) {
                    Button(
                        onClick = {
                            onDismiss()
                            val vpnIntent = VpnService.prepare(context)
                            if (vpnIntent != null) {
                                vpnLauncher.launch(vpnIntent)
                            } else {
                                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                                    action = PacketTunnelProvider.ACTION_START
                                }
                                context.startService(intent)
                                context.getSharedPreferences("twopchat_prefs", Context.MODE_PRIVATE).edit {
                                    putBoolean("settings_yggdrasil", true)
                                }
                                Toast.makeText(context, if (appLanguage == "Русский") "Yggdrasil успешно включен!" else "Yggdrasil enabled successfully!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Включить Yggdrasil" else "Enable Yggdrasil",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun ChatForwardDialog(
    context: Context,
    peerName: String,
    username: String,
    messageToForward: Message?,
    persistEnabled: Boolean,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onPersistDatabase: (() -> Unit) -> Unit,
) {
    if (messageToForward == null) return
    val sharedPrefs = context.getSharedPreferences("twopchat_prefs", Context.MODE_PRIVATE)
    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
    val groups = com.example.twopchat.group.runtime.GroupChatCoordinator.visibleGroups()

    val groupItems = groups.map { group ->
        com.example.twopchat.ui.common.RecipientItem(
            id = "group_${group.groupId}",
            title = group.title,
            subtitle = if (appLanguage == "Русский") "Группа" else "Group",
            isOnline = true,
            isGroup = true,
        )
    }

    val peerItems = activeSet.filter { it != peerName }.map { name ->
        val avatar = P2PMessageRelay.peerAvatars[name]
        val isOnline = P2PMessageRelay.peerSessionStates[name] == true || name == "Saved Messages"
        val subtitle = when {
            name == "Saved Messages" -> if (appLanguage == "Русский") "Личное хранилище" else "Personal storage"
            isOnline -> if (appLanguage == "Русский") "В сети" else "Online"
            else -> if (appLanguage == "Русский") "Был(а) недавно" else "Offline"
        }
        val initials = if (name == "Saved Messages") {
            "🔖"
        } else if (name.contains(" ")) {
            name.split(" ").map { it.take(1) }.joinToString("")
        } else {
            name.take(2).uppercase()
        }
        com.example.twopchat.ui.common.RecipientItem(
            id = "peer_$name",
            title = name,
            subtitle = subtitle,
            isOnline = isOnline,
            avatarBitmap = avatar,
            initials = initials,
            isGroup = false,
        )
    }

    com.example.twopchat.ui.common.RecipientPickerDialog(
        title = if (appLanguage == "Русский") "Переслать сообщение" else "Forward Message",
        searchPlaceholder = if (appLanguage == "Русский") "Поиск получателя..." else "Search recipient...",
        recipients = groupItems + peerItems,
        primaryColor = primaryColor,
        onDismiss = onDismiss,
        onRecipientSelected = { item ->
            val currentMsg = messageToForward
            if (item.isGroup) {
                val targetGroupId = item.id.removePrefix("group_")
                val textToForward = currentMsg.text
                onDismiss()
                if (currentMsg.attachmentUri != null && currentMsg.attachmentName != null) {
                    com.example.twopchat.group.runtime.GroupChatCoordinator.sendAttachment(
                        targetGroupId,
                        currentMsg.attachmentName,
                        ""
                    )
                } else {
                    com.example.twopchat.group.runtime.GroupChatCoordinator.sendMessage(targetGroupId, textToForward, null)
                }
                Toast.makeText(context, if (appLanguage == "Русский") "Переслано в ${item.title}" else "Forwarded to ${item.title}", Toast.LENGTH_SHORT).show()
            } else {
                val chatName = item.id.removePrefix("peer_")
                if (P2PPreferences.isPeerIdentityChangePending(context, chatName)) {
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "В чате $chatName отправка приостановлена из-за смены ключа" else "Sending to $chatName is paused because its key changed",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@RecipientPickerDialog
                }
                val textToForward = currentMsg.text
                val forwardTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val forwardEndpoint = P2PMessageRelay.peerEndpoints[chatName]
                val fwdInitialStatus = if (forwardEndpoint != null || chatName == "Saved Messages") "SENT" else "PENDING"
                val fwdMsg = Message(
                    id = java.util.UUID.randomUUID().toString(),
                    text = textToForward,
                    isMe = true,
                    timestamp = forwardTime,
                    attachmentType = currentMsg.attachmentType,
                    attachmentUri = currentMsg.attachmentUri,
                    attachmentName = currentMsg.attachmentName,
                    status = fwdInitialStatus
                )

                if (persistEnabled || fwdInitialStatus == "PENDING") {
                    onPersistDatabase {
                        com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).saveMessage(chatName, fwdMsg)
                    }
                }
                sharedPrefs.edit { putString("last_msg_$chatName", com.example.twopchat.security.SecureStorage.encrypt("You: $textToForward")) }

                if (forwardEndpoint != null && chatName != "Saved Messages") {
                    val attachUri = currentMsg.attachmentUri
                    if (currentMsg.attachmentType != null && attachUri != null) {
                        P2PMessageRelay.sendFile(context, chatName, forwardEndpoint, attachUri, fwdMsg.id) { success ->
                            if (!success) {
                                onPersistDatabase {
                                    com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).updateMessageStatus(fwdMsg.id, "PENDING")
                                }
                            }
                        }
                    } else {
                        P2PMessageRelay.sendMessage(context, forwardEndpoint, username, textToForward) { success ->
                            if (!success) {
                                onPersistDatabase {
                                    com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).updateMessageStatus(fwdMsg.id, "PENDING")
                                }
                            }
                        }
                    }
                }

                Toast.makeText(context, if (appLanguage == "Русский") "Переслано в $chatName" else "Forwarded to $chatName", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    )
}
