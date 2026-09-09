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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.R
import com.example.twopchat.bridge.P2PBridgeProvider
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
    val discoveryCode = remember { P2PPreferences.getRendezvousCode(context) }
    val fingerprint = remember { P2PBridgeProvider.get(context).getLocalFingerprint() }
    val listenerPort = remember { P2PMessageRelay.listenerPort(context) }
    val localIp = remember { P2PMessageRelay.getLocalIpAddress(context) }
    val yggIp = remember { P2PMessageRelay.getYggdrasilAddress() }

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
    val appLanguage = remember(context) { com.example.twopchat.config.P2PPreferences.getAppLanguage(context) }
    var showShareContactDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = surfaceColor,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Приглашение в группу",
                                en = "Group Invitation",
                                de = "Gruppeneinladung",
                                es = "Invitación al grupo",
                                fr = "Invitation de groupe",
                                pt = "Convite do grupo",
                                tr = "Grup Daveti"
                            ),
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
                        contentDescription = "QR: $groupTitle",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = inviteLink,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                com.example.twopchat.copyTextToClipboard(context, "Group Invite", inviteLink)
                                Toast.makeText(
                                    context,
                                    com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "Ссылка скопирована!",
                                        en = "Link copied!",
                                        de = "Link kopiert!",
                                        es = "¡Enlace copiado!",
                                        fr = "Lien copié !",
                                        pt = "Link copiado!",
                                        tr = "Bağlantı kopyalandı!"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
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
                val shareChooserTitle = com.example.twopchat.data.Localizations.tr(
                    appLanguage,
                    ru = "Поделиться приглашением",
                    en = "Share invitation",
                    de = "Einladung teilen",
                    es = "Compartir invitación",
                    fr = "Partager l'invitation",
                    pt = "Compartilhar convite",
                    tr = "Daveti Paylaş"
                )
                val shareInviteText = com.example.twopchat.data.Localizations.tr(
                    appLanguage,
                    ru = "Приглашение в группу «$groupTitle» в 2PChat:\n\n$inviteLink",
                    en = "Invitation to group \"$groupTitle\" in 2PChat:\n\n$inviteLink",
                    de = "Einladung zur Gruppe „$groupTitle“ in 2PChat:\n\n$inviteLink",
                    es = "Invitación al grupo \"$groupTitle\" en 2PChat:\n\n$inviteLink",
                    fr = "Invitation au groupe « $groupTitle » dans 2PChat :\n\n$inviteLink",
                    pt = "Convite para o grupo \"$groupTitle\" no 2PChat:\n\n$inviteLink",
                    tr = "2PChat\'teki \"$groupTitle\" grubuna davet:\n\n$inviteLink"
                )

                Button(
                    onClick = {
                        if (candidates.isNotEmpty()) {
                            showShareContactDialog = true
                        } else {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareInviteText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, shareChooserTitle))
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
                    Text(
                        text = com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Отправить в 2PChat",
                            en = "Send in 2PChat",
                            de = "In 2PChat senden",
                            es = "Enviar en 2PChat",
                            fr = "Envoyer dans 2PChat",
                            pt = "Enviar no 2PChat",
                            tr = "2PChat ile Gönder"
                        ),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
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
                                putExtra(Intent.EXTRA_TEXT, shareInviteText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, shareChooserTitle))
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Внешний доступ",
                                en = "Share externally",
                                de = "Extern teilen",
                                es = "Compartir fuera",
                                fr = "Partager en externe",
                                pt = "Compartilhar fora",
                                tr = "Harici Paylaş"
                            ),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            com.example.twopchat.copyTextToClipboard(context, "Group Invite", inviteLink)
                            Toast.makeText(
                                context,
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Ссылка скопирована в буфер!",
                                    en = "Link copied to clipboard!",
                                    de = "Link in Zwischenablage kopiert!",
                                    es = "¡Enlace copiado al portapapeles!",
                                    fr = "Lien copié dans le presse-papiers !",
                                    pt = "Link copiado para a área de transferência!",
                                    tr = "Bağlantı panoya kopyalandı!"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Скопировать",
                                en = "Copy link",
                                de = "Link kopieren",
                                es = "Copiar enlace",
                                fr = "Copier le lien",
                                pt = "Copiar link",
                                tr = "Bağlantıyı Kopyala"
                            ),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    // Modal to pick a 1-on-1 contact inside 2PChat
    if (showShareContactDialog) {
        val effectiveCandidates = remember(candidates) {
            if (candidates.isNotEmpty()) {
                candidates
            } else {
                com.example.twopchat.config.P2PPreferences.getAllKnownPeers(context).map { peerName ->
                    val avatar = P2PMessageRelay.peerAvatars[peerName]
                    val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
                    val fp = com.example.twopchat.config.P2PPreferences.getPeerFingerprint(context, peerName).orEmpty()
                    GroupContactSummary(
                        contactId = peerName,
                        displayName = peerName,
                        secondaryText = if (fp.isNotBlank()) fp.take(16) else peerName,
                        isOnline = isOnline,
                    )
                }
            }
        }
        val recipientItems = remember(effectiveCandidates) {
            effectiveCandidates.map { contact ->
                val peerName = contact.displayName
                val avatar = P2PMessageRelay.peerAvatars[peerName]
                RecipientItem(
                    id = contact.contactId,
                    title = contact.displayName,
                    subtitle = if (contact.isOnline) {
                        com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "В сети",
                            en = "Online",
                            de = "Online",
                            es = "En línea",
                            fr = "En ligne",
                            pt = "Online",
                            tr = "Çevrimiçi"
                        )
                    } else {
                        com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Был(а) недавно",
                            en = "Recently seen",
                            de = "Kürzlich gesehen",
                            es = "Visto recientemente",
                            fr = "Vu récemment",
                            pt = "Visto recentemente",
                            tr = "Son görülme yakınlarda"
                        )
                    },
                    isOnline = contact.isOnline,
                    avatarBitmap = avatar,
                    initials = contact.displayName.take(2).uppercase(),
                    isGroup = false,
                )
            }
        }

        RecipientPickerDialog(
            title = com.example.twopchat.data.Localizations.tr(
                appLanguage,
                ru = "Выберите чат",
                en = "Select Chat",
                de = "Chat auswählen",
                es = "Seleccionar chat",
                fr = "Sélectionner un chat",
                pt = "Selecionar conversa",
                tr = "Sohbet Seçin"
            ),
            searchPlaceholder = com.example.twopchat.data.Localizations.tr(
                appLanguage,
                ru = "Поиск получателя...",
                en = "Search recipient...",
                de = "Empfänger suchen...",
                es = "Buscar destinatario...",
                fr = "Rechercher un destinataire...",
                pt = "Buscar destinatário...",
                tr = "Alıcı ara..."
            ),
            recipients = recipientItems,
            primaryColor = primaryColor,
            onDismiss = { showShareContactDialog = false },
            onRecipientSelected = { item ->
                showShareContactDialog = false
                val peerName = item.title
                val shareText = com.example.twopchat.data.Localizations.tr(
                    appLanguage,
                    ru = "👋 Приглашение в группу «$groupTitle»!\n\nСсылка для входа:\n$inviteLink",
                    en = "👋 Invitation to group \"$groupTitle\"!\n\nJoin link:\n$inviteLink",
                    de = "👋 Einladung zur Gruppe „$groupTitle“!\n\nBeitrittslink:\n$inviteLink",
                    es = "👋 ¡Invitación al grupo \"$groupTitle\"!\n\nEnlace de acceso:\n$inviteLink",
                    fr = "👋 Invitation au groupe « $groupTitle » !\n\nLien pour rejoindre :\n$inviteLink",
                    pt = "👋 Convite para o grupo \"$groupTitle\"!\n\nLink de entrada:\n$inviteLink",
                    tr = "👋 \"$groupTitle\" grubuna davet!\n\nKatılma bağlantısı:\n$inviteLink"
                )
                P2PMessageRelay.sendMessageToPeer(context, peerName, shareText) { success ->
                    val msg = if (success) {
                        com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Приглашение отправлено $peerName!",
                            en = "Invitation sent to $peerName!",
                            de = "Einladung an $peerName gesendet!",
                            es = "¡Invitación enviada a $peerName!",
                            fr = "Invitation envoyée à $peerName !",
                            pt = "Convite enviado para $peerName!",
                            tr = "Davet $peerName kişisine gönderildi!"
                        )
                    } else {
                        com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Не удалось отправить приглашение: контакт не подключён",
                            en = "Failed to send invitation: contact is not connected",
                            de = "Einladung konnte nicht gesendet werden: Kontakt nicht verbunden",
                            es = "No se pudo enviar la invitación: contacto no conectado",
                            fr = "Impossible d'envoyer l'invitation : contact non connecté",
                            pt = "Falha ao enviar convite: contato não conectado",
                            tr = "Davet gönderilemedi: kişi bağlı değil"
                        )
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
