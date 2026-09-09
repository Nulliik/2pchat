package com.example.twopchat.group.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.group.ui.GroupTimelineMessage
import com.example.twopchat.media.GifStorageManager
import com.example.twopchat.ui.chat.saveFileToPublicDownloads
import java.io.File

@Composable
fun GroupChatMessageOptionsMenu(
    context: Context,
    message: GroupTimelineMessage,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onReactionClick: (String) -> Unit,
    onReply: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onSeenBy: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val appLanguage = remember(context) { com.example.twopchat.config.P2PPreferences.getAppLanguage(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        containerColor = surfaceColor,
        shape = RoundedCornerShape(24.dp),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = com.example.twopchat.data.Localizations.tr(
                        appLanguage,
                        ru = "Действия с сообщением",
                        en = "Message Actions",
                        de = "Nachrichtenaktionen",
                        es = "Acciones del mensaje",
                        fr = "Actions sur le message",
                        pt = "Ações da mensagem",
                        tr = "Mesaj Eylemleri"
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                // Quick Emoji Reactions
                if (message.canReact) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val quickEmojis = listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "👏", "💩", "🎉", "💯")
                        quickEmojis.forEach { emoji ->
                            val userReaction = message.reactions.find { it.emoji == emoji }
                            val isSelected = userReaction?.reactedByMe == true
                            val bgColor = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.12f)

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = bgColor,
                                border = if (isSelected) BorderStroke(1.5.dp, primaryColor) else null,
                                modifier = Modifier
                                    .height(40.dp)
                                    .clickable {
                                        onReactionClick(emoji)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(text = emoji, fontSize = 20.sp)
                                    if (userReaction != null && userReaction.count > 0) {
                                        Text(
                                            text = "${userReaction.count}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else primaryColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = onSurfaceColor.copy(alpha = 0.08f)
                    )
                }

                // 1. Reply
                if (message.canReply) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onReply() }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                            .testTag("reply_${message.messageId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_reply),
                            contentDescription = "Reply",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Ответить",
                                en = "Reply",
                                de = "Antworten",
                                es = "Responder",
                                fr = "Répondre",
                                pt = "Responder",
                                tr = "Yanıtla"
                            ),
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // 2. Pin
                if (message.canPin) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPin() }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                            .testTag("pin_${message.messageId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pin),
                            contentDescription = "Pin",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = if (message.isPinned) {
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Открепить",
                                    en = "Unpin",
                                    de = "Lösen",
                                    es = "Desfijar",
                                    fr = "Détacher",
                                    pt = "Desafixar",
                                    tr = "Sabitlemeyi Kaldır"
                                )
                            } else {
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Закрепить",
                                    en = "Pin",
                                    de = "Anheften",
                                    es = "Fijar",
                                    fr = "Épingler",
                                    pt = "Fixar",
                                    tr = "Sabitle"
                                )
                            },
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // 3. Edit
                if (message.canEdit && message.isMine) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEdit() }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                            .testTag("edit_${message.messageId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_edit),
                            contentDescription = "Edit",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Редактировать",
                                en = "Edit",
                                de = "Bearbeiten",
                                es = "Editar",
                                fr = "Modifier",
                                pt = "Editar",
                                tr = "Düzenle"
                            ),
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // 4. Copy Text
                if (message.text.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                copyTextToClipboard(context, "Message Text", message.text)
                                Toast.makeText(
                                    context,
                                    com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "Текст скопирован",
                                        en = "Text copied",
                                        de = "Text kopiert",
                                        es = "Texto copiado",
                                        fr = "Texte copié",
                                        pt = "Texto copiado",
                                        tr = "Metin kopyalandı"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = "Copy",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Скопировать текст",
                                en = "Copy text",
                                de = "Text kopieren",
                                es = "Copiar texto",
                                fr = "Copier le texte",
                                pt = "Copiar texto",
                                tr = "Metni Kopyala"
                            ),
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // 5. Save GIF / Save File
                message.attachment?.let { att ->
                    val filePath = att.localPath ?: att.fileName
                    val isGif = att.mimeType.contains("gif", ignoreCase = true) || filePath.endsWith(".gif", ignoreCase = true)
                    if (isGif && filePath.isNotBlank() && File(filePath).exists()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    GifStorageManager.save(context, File(filePath))
                                    Toast.makeText(
                                        context,
                                        com.example.twopchat.data.Localizations.tr(
                                            appLanguage,
                                            ru = "Сохранено в Мои GIF",
                                            en = "Saved to My GIFs",
                                            de = "In Meinen GIFs gespeichert",
                                            es = "Guardado en Mis GIFs",
                                            fr = "Enregistré dans Mes GIFs",
                                            pt = "Salvo em Meus GIFs",
                                            tr = "GIF'lerime Kaydedildi"
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add_photo_smiley),
                                contentDescription = "Save GIF",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Сохранить в Мои GIF",
                                    en = "Save to My GIFs",
                                    de = "Zu Meinen GIFs hinzufügen",
                                    es = "Guardar en Mis GIFs",
                                    fr = "Enregistrer dans Mes GIFs",
                                    pt = "Salvar em Meus GIFs",
                                    tr = "GIF'lerime Kaydet"
                                ),
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }
                    }
                    if (filePath.isNotBlank() && File(filePath).exists()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val savedUri = saveFileToPublicDownloads(context, filePath, att.fileName)
                                    if (savedUri != null) {
                                        Toast.makeText(
                                            context,
                                            com.example.twopchat.data.Localizations.tr(
                                                appLanguage,
                                                ru = "Файл сохранён в Загрузки",
                                                en = "File saved to Downloads",
                                                de = "Datei in Downloads gespeichert",
                                                es = "Archivo guardado en Descargas",
                                                fr = "Fichier enregistré dans Téléchargements",
                                                pt = "Arquivo salvo em Downloads",
                                                tr = "Dosya İndirilenler'e kaydedildi"
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            com.example.twopchat.data.Localizations.tr(
                                                appLanguage,
                                                ru = "Ошибка сохранения",
                                                en = "Save failed",
                                                de = "Fehler beim Speichern",
                                                es = "Error al guardar",
                                                fr = "Échec de l'enregistrement",
                                                pt = "Falha ao salvar",
                                                tr = "Kaydetme başarısız oldu"
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_download),
                                contentDescription = "Download",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Скачать файл",
                                    en = "Download file",
                                    de = "Datei herunterladen",
                                    es = "Descargar archivo",
                                    fr = "Télécharger le fichier",
                                    pt = "Baixar arquivo",
                                    tr = "Dosyayı İndir"
                                ),
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }
                    }
                }

                // 6. Forward
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onForward() }
                        .padding(vertical = 12.dp, horizontal = 12.dp)
                        .testTag("forward_${message.messageId}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_forward),
                        contentDescription = "Forward",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Переслать",
                            en = "Forward",
                            de = "Weiterleiten",
                            es = "Reenviar",
                            fr = "Transférer",
                            pt = "Encaminhar",
                            tr = "İlet"
                        ),
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }

                // 7. Seen By
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSeenBy() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_msg_single_check),
                        contentDescription = "Seen By",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Просмотрено (${message.readByMembers.size})",
                            en = "Seen by (${message.readByMembers.size})",
                            de = "Gesehen von (${message.readByMembers.size})",
                            es = "Visto por (${message.readByMembers.size})",
                            fr = "Vu par (${message.readByMembers.size})",
                            pt = "Visto por (${message.readByMembers.size})",
                            tr = "Görenler (${message.readByMembers.size})"
                        ),
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }

                // 8. Delete
                if (message.canDelete) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDelete() }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                            .testTag("delete_${message.messageId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = "Delete",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Удалить",
                                en = "Delete",
                                de = "Löschen",
                                es = "Eliminar",
                                fr = "Supprimer",
                                pt = "Excluir",
                                tr = "Sil"
                            ),
                            fontSize = 15.sp,
                            color = Color.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 9. Select
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_select),
                        contentDescription = "Select",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Выделить",
                            en = "Select",
                            de = "Auswählen",
                            es = "Seleccionar",
                            fr = "Sélectionner",
                            pt = "Selecionar",
                            tr = "Seç"
                        ),
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }
            }
        }
    )
}
