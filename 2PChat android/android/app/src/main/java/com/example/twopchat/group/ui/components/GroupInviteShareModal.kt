package com.example.twopchat.group.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences
import com.example.twopchat.PythonBridge
import com.example.twopchat.R
import com.example.twopchat.ui.main.buildContactQrPayload
import com.example.twopchat.group.ui.GroupContactSummary
import com.example.twopchat.ui.common.QrCodeImage
import com.example.twopchat.ui.common.RecipientItem
import com.example.twopchat.ui.common.RecipientPickerDialog

@Composable
internal fun GroupInviteQrModal(
    groupTitle: String,
    groupId: String,
    inviteToken: String,
    candidates: List<GroupContactSummary>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    val prefs = remember(context) { P2PPreferences.prefs(context) }
    val username = remember(prefs) {
        prefs.getString("username_profile", "2PChat User").orEmpty()
    }
    val discoveryCode = remember { PythonBridge.getOrCreateDiscoveryCode() }
    val fingerprint = remember { PythonBridge.getLocalFingerprint() }
    val listenerPort = remember { P2PMessageRelay.listenerPort(context) }
    val localIp = remember { PythonBridge.getLocalIpAddress(false) }
    val yggIp = remember { PythonBridge.getYggdrasilAddress() }

    val inviteLink = remember(
        username, discoveryCode, fingerprint, listenerPort, localIp, yggIp, groupId, inviteToken
    ) {
        buildContactQrPayload(
            nickname = username,
            discoveryCode = discoveryCode,
            fingerprint = fingerprint,
            localIpv4 = localIp.takeUnless { it == "127.0.0.1" }.orEmpty(),
            publicIpv4 = "",
            ipv6 = yggIp,
            listenerPort = listenerPort,
        ) + "&group=" + Uri.encode(groupId) +
            "&group_token=" + Uri.encode(inviteToken)
    }

    var showShareContactDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = surfaceColor,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with title & close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Приглашение в группу",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        )
                        Text(
                            text = groupTitle,
                            fontSize = 13.sp,
                            color = primaryColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Beautiful QR Code Frame
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(200.dp)
                        .padding(4.dp)
                ) {
                    QrCodeImage(
                        payload = inviteLink,
                        contentDescription = "QR-приглашение в $groupTitle",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Clean Link Box
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_quick_link),
                            contentDescription = "Link",
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "2pchat.join/$groupTitle",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceColor,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                com.example.twopchat.copyTextToClipboard(context, "Group Invite", inviteLink)
                                Toast.makeText(context, "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = "Copy",
                                tint = primaryColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Share Buttons Row
                Button(
                    onClick = {
                        if (candidates.isNotEmpty()) {
                            showShareContactDialog = true
                        } else {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "Приглашение в группу «$groupTitle» в 2PChat:\n\n$inviteLink")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Поделиться приглашением"))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_forward),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить в 2PChat", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "Приглашение в группу «$groupTitle» в 2PChat:\n\n$inviteLink")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Поделиться приглашением"))
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text("Внешний доступ", fontSize = 12.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            com.example.twopchat.copyTextToClipboard(context, "Group Invite", inviteLink)
                            Toast.makeText(context, "Ссылка скопирована в буфер!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text("Скопировать", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    // Modal to pick a 1-on-1 contact inside 2PChat
    if (showShareContactDialog) {
        val recipientItems = candidates.map { contact ->
            val peerName = contact.displayName
            val avatar = P2PMessageRelay.peerAvatars[peerName]
            RecipientItem(
                id = contact.contactId,
                title = contact.displayName,
                subtitle = if (contact.isOnline) "В сети" else "Был(а) недавно",
                isOnline = contact.isOnline,
                avatarBitmap = avatar,
                initials = contact.displayName.take(2).uppercase(),
                isGroup = false,
            )
        }

        RecipientPickerDialog(
            title = "Выберите чат",
            searchPlaceholder = "Поиск получателя...",
            recipients = recipientItems,
            primaryColor = primaryColor,
            onDismiss = { showShareContactDialog = false },
            onRecipientSelected = { item ->
                showShareContactDialog = false
                val peerName = item.title
                val shareText = "👋 Приглашение в группу «$groupTitle»!\n\nСсылка для входа:\n$inviteLink"
                P2PMessageRelay.sendMessageToPeer(context, peerName, shareText) { success ->
                    val msg = if (success) {
                        "Приглашение отправлено $peerName!"
                    } else {
                        "Не удалось отправить приглашение: контакт не подключён"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
