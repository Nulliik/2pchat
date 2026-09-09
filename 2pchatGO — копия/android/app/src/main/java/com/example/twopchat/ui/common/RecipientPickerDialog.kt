package com.example.twopchat.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.twopchat.R
import com.example.twopchat.theme.MintGreen
import com.example.twopchat.theme.StealthBlack

data class RecipientItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val isOnline: Boolean = false,
    val avatarBitmap: Bitmap? = null,
    val initials: String = "",
    val isGroup: Boolean = false,
)

@Composable
fun RecipientPickerDialog(
    title: String,
    searchPlaceholder: String = "",
    recipients: List<RecipientItem>,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    onDismiss: () -> Unit,
    onRecipientSelected: (RecipientItem) -> Unit,
) {
    val context = LocalContext.current
    val appLanguage = remember(context) { com.example.twopchat.config.P2PPreferences.getAppLanguage(context) }
    val effectivePlaceholder = searchPlaceholder.ifBlank {
        com.example.twopchat.data.Localizations.tr(
            appLanguage,
            ru = "Поиск получателя...",
            en = "Search recipient...",
            de = "Empfänger suchen...",
            es = "Buscar destinatario...",
            fr = "Rechercher un destinataire...",
            pt = "Buscar destinatário...",
            tr = "Alıcı ara..."
        )
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

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
                    .fillMaxWidth()
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Bar
                val context = androidx.compose.ui.platform.LocalContext.current
                var searchQuery by remember { mutableStateOf("") }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = context,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = onSurfaceColor,
                        fontSize = 14.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    cursorBrush = SolidColor(onSurfaceColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .border(
                            width = 0.5.dp,
                            color = onSurfaceColor.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    decorationBox = { innerTextField ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = effectivePlaceholder,
                                        color = onSurfaceVariant.copy(alpha = 0.5f),
                                        fontSize = 14.sp,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                                innerTextField()
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                val filtered = remember(recipients, searchQuery) {
                    if (searchQuery.isBlank()) recipients
                    else recipients.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                            it.subtitle.contains(searchQuery, ignoreCase = true)
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Нет доступных чатов",
                                    en = "No available chats",
                                    de = "Keine verfügbaren Chats",
                                    es = "No hay chats disponibles",
                                    fr = "Aucun chat disponible",
                                    pt = "Nenhum chat disponível",
                                    tr = "Kullanılabilir sohbet yok"
                                )
                            } else {
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Ничего не найдено",
                                    en = "No results found",
                                    de = "Keine Ergebnisse gefunden",
                                    es = "No se encontraron resultados",
                                    fr = "Aucun résultat trouvé",
                                    pt = "Nenhum resultado encontrado",
                                    tr = "Sonuç bulunamadı"
                                )
                            },
                            color = onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onRecipientSelected(item) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar Circle
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(
                                            brush = Brush.linearGradient(
                                                colors = listOf(primaryColor.copy(alpha = 0.2f), primaryColor.copy(alpha = 0.08f))
                                            ),
                                            shape = CircleShape
                                        )
                                ) {
                                    if (item.avatarBitmap != null) {
                                        Image(
                                            bitmap = item.avatarBitmap.asImageBitmap(),
                                            contentDescription = item.title,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else if (item.isGroup) {
                                        Icon(
                                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_chats),
                                            contentDescription = item.title,
                                            tint = primaryColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            text = item.initials.ifBlank { item.title.take(2).uppercase() },
                                            color = primaryColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = onSurfaceColor,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    color = if (item.isOnline) Color(0xFF4CAF50) else onSurfaceVariant.copy(alpha = 0.4f),
                                                    shape = CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.subtitle.ifBlank {
                                                if (item.isOnline) {
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
                                                }
                                            },
                                            color = onSurfaceVariant,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { onRecipientSelected(item) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(primaryColor, CircleShape)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_forward),
                                        contentDescription = "Send",
                                        tint = if (primaryColor == MintGreen) StealthBlack else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
