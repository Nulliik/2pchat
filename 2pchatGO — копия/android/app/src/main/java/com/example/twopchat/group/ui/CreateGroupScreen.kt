package com.example.twopchat.group.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun CreateGroupScreen(
    state: CreateGroupUiState,
    controller: GroupUiController,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appLanguage = remember(context) { com.example.twopchat.config.P2PPreferences.prefs(context).getString("app_language", "Русский") ?: "Русский" }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var selectedContactIds by remember(state.knownContacts) {
        mutableStateOf<Set<String>>(
            state.knownContacts.filter(GroupContactSummary::isAlreadySelected)
                .map(GroupContactSummary::contactId)
                .toSet()
        )
    }
    val cleanTitle = title.trim()
    val canCreate = cleanTitle.isNotEmpty() &&
        !state.isCreating

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Surface(
                color = surfaceColor,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = controller::onBack,
                        modifier = Modifier.testTag("create_group_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onSurfaceColor
                        )
                    }
                    Text(
                        text = com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Новая группа",
                            en = "New Group",
                            de = "Neue Gruppe",
                            es = "Nuevo grupo",
                            fr = "Nouveau groupe",
                            pt = "Novo grupo",
                            tr = "Yeni Grup"
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_title_input"),
                    label = {
                        Text(
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Название группы",
                                en = "Group Title",
                                de = "Gruppenname",
                                es = "Nombre del grupo",
                                fr = "Nom du groupe",
                                pt = "Nome do grupo",
                                tr = "Grup Adı"
                            )
                        )
                    },
                    placeholder = {
                        Text(
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Например: Команда 2PChat",
                                en = "Example: 2PChat Team",
                                de = "z.B.: 2PChat Team",
                                es = "Ejemplo: Equipo 2PChat",
                                fr = "Exemple : Équipe 2PChat",
                                pt = "Exemplo: Equipe 2PChat",
                                tr = "Örn: 2PChat Ekibi"
                            )
                        )
                    },
                    supportingText = {
                        if (cleanTitle.isEmpty() && title.isNotEmpty()) {
                            Text(
                                com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Название не может быть пустым",
                                    en = "Title cannot be empty",
                                    de = "Name darf nicht leer sein",
                                    es = "El nombre no puede estar vacío",
                                    fr = "Le nom ne peut pas être vide",
                                    pt = "O nome não pode estar vazio",
                                    tr = "Başlık boş olamaz"
                                )
                            )
                        }
                    },
                    isError = cleanTitle.isEmpty() && title.isNotEmpty(),
                    singleLine = true,
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = context,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = primaryColor.copy(alpha = 0.3f)
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_description_input"),
                    label = {
                        Text(
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Описание (опционально)",
                                en = "Description (optional)",
                                de = "Beschreibung (optional)",
                                es = "Descripción (opcional)",
                                fr = "Description (facultatif)",
                                pt = "Descrição (opcional)",
                                tr = "Açıklama (isteğe bağlı)"
                            )
                        )
                    },
                    placeholder = {
                        Text(
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "О чем эта группа...",
                                en = "What is this group about...",
                                de = "Worum geht es in dieser Gruppe...",
                                es = "¿De qué trata este grupo...",
                                fr = "De quoi parle ce groupe...",
                                pt = "Sobre o que é este grupo...",
                                tr = "Bu grup ne hakkında..."
                            )
                        )
                    },
                    minLines = 2,
                    maxLines = 3,
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = context,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = primaryColor.copy(alpha = 0.3f)
                    )
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = com.example.twopchat.data.Localizations.tr(
                        appLanguage,
                        ru = "Участники · Выбрано: ${selectedContactIds.size}",
                        en = "Members · Selected: ${selectedContactIds.size}",
                        de = "Mitglieder · Ausgewählt: ${selectedContactIds.size}",
                        es = "Miembros · Seleccionados: ${selectedContactIds.size}",
                        fr = "Membres · Sélectionnés : ${selectedContactIds.size}",
                        pt = "Membros · Selecionados: ${selectedContactIds.size}",
                        tr = "Üyeler · Seçilen: ${selectedContactIds.size}"
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
                Text(
                    text = com.example.twopchat.data.Localizations.tr(
                        appLanguage,
                        ru = "Это необязательно: участников можно добавить после создания. Вы станете владельцем группы.",
                        en = "Optional: members can be added after creation. You will become the group owner.",
                        de = "Optional: Mitglieder können später hinzugefügt werden. Sie werden Gruppenbesitzer.",
                        es = "Opcional: puedes añadir miembros más tarde. Serás el propietario del grupo.",
                        fr = "Facultatif : les membres peuvent être ajoutés après création. Vous deviendrez le propriétaire du groupe.",
                        pt = "Opcional: membros podem ser adicionados após a criação. Você se tornará o proprietário do grupo.",
                        tr = "İsteğe bağlı: üyeler oluşturulduktan sonra eklenebilir. Grubun kurucusu siz olacaksınız."
                    ),
                    fontSize = 12.sp,
                    color = onSurfaceColor.copy(alpha = 0.6f)
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("known_contacts_list")
                ) {
                    if (state.knownContacts.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "Контакты пока не найдены",
                                        en = "No contacts found yet",
                                        de = "Noch keine Kontakte gefunden",
                                        es = "Aún no se han encontrado contactos",
                                        fr = "Aucun contact trouvé pour l'instant",
                                        pt = "Nenhum contato encontrado ainda",
                                        tr = "Henüz kişi bulunamadı"
                                    ),
                                    fontSize = 13.sp,
                                    color = onSurfaceColor.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    items(state.knownContacts, key = GroupContactSummary::contactId) { contact ->
                        val selected = contact.contactId in selectedContactIds
                        val initials = contact.displayName.take(2).uppercase().ifBlank { "C" }
                        val avatarColor = remember(contact.displayName) {
                            val colors = listOf(
                                Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60),
                                Color(0xFFF4511E), Color(0xFF7CB342), Color(0xFF00ACC1)
                            )
                            colors[abs(contact.displayName.hashCode()) % colors.size]
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isCreating) {
                                    selectedContactIds = selectedContactIds.toMutableSet().apply {
                                        if (selected) remove(contact.contactId) else add(contact.contactId)
                                    }
                                }
                                .testTag("contact_${contact.contactId}")
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                                enabled = !state.isCreating
                            )
                            Spacer(Modifier.width(8.dp))
                            val contactAvatarBitmap = com.example.twopchat.relay.P2PMessageRelay.peerAvatars[contact.displayName]
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(avatarColor),
                                contentAlignment = Alignment.Center
                            ) {
                                if (contactAvatarBitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = contactAvatarBitmap.asImageBitmap(),
                                        contentDescription = contact.displayName,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Text(
                                        text = initials,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(contact.displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                val detail = contact.secondaryText.ifBlank {
                                    if (contact.isOnline) {
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
                                            ru = "Не в сети",
                                            en = "Offline",
                                            de = "Offline",
                                            es = "Desconectado",
                                            fr = "Hors ligne",
                                            pt = "Offline",
                                            tr = "Çevrimdışı"
                                        )
                                    }
                                }
                                Text(
                                    detail,
                                    fontSize = 11.sp,
                                    color = if (contact.isOnline) Color(0xFF2E7D32) else onSurfaceColor.copy(alpha = 0.5f)
                                )
                            }
                        }
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.08f))
                    }
                }

                state.errorMessage?.let {
                    Text(
                        text = it,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag("create_group_error"),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        controller.createGroup(
                            title = cleanTitle,
                            description = description.trim(),
                            contactIds = selectedContactIds
                        )
                    },
                    enabled = canCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(top = 4.dp)
                        .testTag("create_group_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (state.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text(
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Создать группу",
                                en = "Create Group",
                                de = "Gruppe erstellen",
                                es = "Crear grupo",
                                fr = "Créer le groupe",
                                pt = "Criar grupo",
                                tr = "Grup Oluştur"
                            ),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
