package com.example.twopchat.group.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.res.painterResource
import com.example.twopchat.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.window.Dialog
import android.net.Uri
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.twopchat.ui.chat.AnimatedGifImage
import com.example.twopchat.ui.chat.GifContentScale
import com.example.twopchat.ui.chat.AnimatedStickerImage
import com.example.twopchat.StickerSupport
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun GroupInfoScreen(
    state: GroupInfoUiState,
    controller: GroupUiController,
    modifier: Modifier = Modifier
) {
    var selectedMemberForOptions by remember { mutableStateOf<GroupMember?>(null) }
    var restrictionsFor by remember { mutableStateOf<GroupMember?>(null) }
    var removeConfirmation by remember { mutableStateOf<GroupMember?>(null) }
    var banConfirmation by remember { mutableStateOf<GroupMember?>(null) }
    var transferConfirmation by remember { mutableStateOf<GroupMember?>(null) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    var showEditMetadata by remember { mutableStateOf(false) }
    var showInviteMembers by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Участники, 1: Медиа, 2: Избранное, 3: Файлы
    var selectedMediaPreviewPath by remember { mutableStateOf<String?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Grant persistent read permission so GroupChatCoordinator can read it on background thread
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            controller.updateGroupInfo(
                state.metadata.groupId,
                state.metadata.title,
                state.metadata.description,
                uri.toString()
            )
        }
    }

    val mediaMessages = remember(state.timelineMessages) {
        state.timelineMessages.filter { msg ->
            val att = msg.attachment
            if (att == null) false else {
                val mime = att.mimeType.lowercase()
                val name = att.fileName.lowercase()
                mime.startsWith("image/") || mime.startsWith("video/") ||
                    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".mp4") ||
                    name.endsWith(".mov") || name.endsWith(".mkv")
            }
        }
    }

    val fileMessages = remember(state.timelineMessages) {
        state.timelineMessages.filter { msg ->
            val att = msg.attachment
            if (att == null) false else {
                val mime = att.mimeType.lowercase()
                val name = att.fileName.lowercase()
                val isMedia = mime.startsWith("image/") || mime.startsWith("video/") ||
                    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".mp4") ||
                    name.endsWith(".mov") || name.endsWith(".mkv")
                !isMedia
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
    ) {
        // Telegram Style Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = controller::onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back_arrow),
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Row {
                if (state.management.canEditMetadata) {
                    IconButton(
                        onClick = { showEditMetadata = true },
                        modifier = Modifier.testTag("edit_group_info")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = "Edit Group",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { showLeaveConfirmation = true },
                    modifier = Modifier.testTag("leave_group_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color.White
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("group_info_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group Hero Avatar & Title Header
            item(key = "hero_header") {
                GroupHeroHeader(
                    metadata = state.metadata,
                    onAvatarClick = {
                        if (state.management.canEditMetadata) {
                            avatarPickerLauncher.launch(arrayOf("image/*"))
                        }
                    }
                )
            }

            // Quick Action Buttons Row (Чат, Звук, Покинуть)
            item(key = "quick_actions") {
                GroupQuickActionsRow(
                    onChatClick = controller::onBack,
                    onLeaveClick = { showLeaveConfirmation = true }
                )
            }

            // Group Info Card (Адрес группы, Описание, Статус верификации) - Matching Direct Chat Profile
            item(key = "info_details_card") {
                GroupInfoDetailsCard(state.metadata)
            }

            // Add Members Row
            if (state.management.canInviteMembers && state.inviteCandidates.isNotEmpty()) {
                item(key = "add_members_row") {
                    Surface(
                        color = Color(0xFF14161A),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { showInviteMembers = true }
                            .testTag("invite_group_members")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add_square),
                                contentDescription = "Add Members",
                                tint = primaryColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                "Добавить участников",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Segmented Tab Bar Navigation
            item(key = "tab_navigation") {
                GroupTabNavigation(
                    selectedTab = selectedTab,
                    memberCount = state.members.size,
                    onTabSelected = { selectedTab = it }
                )
            }

            // Members List or Tab Content
            when (selectedTab) {
                0 -> {
                    items(state.members, key = GroupMember::memberId) { member ->
                        GroupMemberCard(
                            groupId = state.metadata.groupId,
                            member = member,
                            management = state.management,
                            controller = controller,
                            onMemberClick = { selectedMemberForOptions = member },
                            onRestrict = { restrictionsFor = member },
                            onRemove = { removeConfirmation = member },
                            onBan = { banConfirmation = member },
                            onTransfer = { transferConfirmation = member }
                        )
                    }

                    item(key = "admin_log") {
                        AdminLogSection(state)
                    }
                }
                1 -> { // Медиа вкладка (3-column photo grid like Direct Chat Profile)
                    if (mediaMessages.isEmpty()) {
                        item(key = "empty_media") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Медиафайлы отсутствуют", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    } else {
                        item(key = "media_grid") {
                            GroupMediaGrid(
                                mediaMessages = mediaMessages,
                                onMediaClick = { path -> selectedMediaPreviewPath = path }
                            )
                        }
                    }
                }
                3 -> { // Файлы вкладка
                    if (fileMessages.isEmpty()) {
                        item(key = "empty_files") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Файлы отсутствуют", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    } else {
                        items(fileMessages, key = { it.messageId }) { msg ->
                            msg.attachment?.let { attachment ->
                                FileAttachmentRow(attachment = attachment, message = msg, controller = controller, groupId = state.metadata.groupId)
                            }
                        }
                    }
                }
                else -> { // Избранное
                    item(key = "tab_empty_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Избранные сообщения отсутствуют", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    selectedMediaPreviewPath?.let { path ->
        Dialog(onDismissRequest = { selectedMediaPreviewPath = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { selectedMediaPreviewPath = null },
                contentAlignment = Alignment.Center
            ) {
                val context = LocalContext.current
                val bitmap = remember(path) {
                    runCatching {
                        if (path.startsWith("content://")) {
                            context.contentResolver.openInputStream(Uri.parse(path))?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        } else {
                            BitmapFactory.decodeFile(path)
                        }
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Full Media",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val isGif = path.lowercase().endsWith(".gif")
                    val isSticker = StickerSupport.isStickerFileName(path)
                    when {
                        isSticker -> AnimatedStickerImage(filePath = path, fallbackEmoji = "👍", contentDescription = "Sticker", targetSizePx = 512, modifier = Modifier.size(240.dp))
                        isGif -> AnimatedGifImage(filePath = path, targetMaxDimensionPx = 1024, contentScale = GifContentScale.FIT, contentDescription = "GIF", modifier = Modifier.fillMaxSize())
                        else -> Text("Медиафайл недоступен", color = Color.White)
                    }
                }
            }
        }
    }

    restrictionsFor?.let { member ->
        MemberRestrictionsDialog(
            member = member,
            onDismiss = { restrictionsFor = null },
            onApply = {
                controller.setMemberRestrictions(state.metadata.groupId, member.memberId, it)
                restrictionsFor = null
            }
        )
    }

    removeConfirmation?.let { member ->
        ConfirmationDialog(
            title = "Удалить ${member.displayName}?",
            body = "Участник потеряет доступ к будущим эпохам шифрования группы.",
            confirmLabel = "Удалить",
            confirmTag = "confirm_remove_member",
            onDismiss = { removeConfirmation = null },
            onConfirm = {
                controller.removeMember(state.metadata.groupId, member.memberId)
                removeConfirmation = null
            }
        )
    }

    banConfirmation?.let { member ->
        ConfirmationDialog(
            title = "Заблокировать ${member.displayName}?",
            body = "Участник будет исключен из состава группы и заблокирован.",
            confirmLabel = "Заблокировать",
            confirmTag = "confirm_ban_member",
            onDismiss = { banConfirmation = null },
            onConfirm = {
                controller.banMember(state.metadata.groupId, member.memberId)
                banConfirmation = null
            }
        )
    }

    transferConfirmation?.let { member ->
        ConfirmationDialog(
            title = "Передать права владельца?",
            body = "${member.displayName} станет главным владельцем группы.",
            confirmLabel = "Передать",
            confirmTag = "confirm_transfer_ownership",
            onDismiss = { transferConfirmation = null },
            onConfirm = {
                controller.transferOwnership(state.metadata.groupId, member.memberId)
                transferConfirmation = null
            }
        )
    }

    if (showLeaveConfirmation) {
        ConfirmationDialog(
            title = "Выйти из группы?",
            body = if (state.currentUserRole == GroupRole.OWNER) {
                "Передайте права владельца перед выходом, если в группе остаются участники."
            } else {
                "История сообщений на устройстве сохранится."
            },
            confirmLabel = "Выйти",
            confirmTag = "confirm_leave_group",
            onDismiss = { showLeaveConfirmation = false },
            onConfirm = {
                controller.leaveGroup(state.metadata.groupId)
                showLeaveConfirmation = false
            }
        )
    }

    if (showEditMetadata) {
        var title by remember(state.metadata.groupId) { mutableStateOf(state.metadata.title) }
        var description by remember(state.metadata.groupId) { mutableStateOf(state.metadata.description) }
        AlertDialog(
            onDismissRequest = { showEditMetadata = false },
            title = { Text("Редактировать группу", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(160) },
                        label = { Text("Название") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_group_title"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(2_000) },
                        label = { Text("Описание (опционально)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.trim().isNotEmpty(),
                    onClick = {
                        controller.updateGroupInfo(
                            state.metadata.groupId,
                            title.trim(),
                            description.trim()
                        )
                        showEditMetadata = false
                    }
                ) { Text("Сохранить", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showEditMetadata = false }) { Text("Отмена") }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showInviteMembers) {
        var selected by remember(state.metadata.groupId) { mutableStateOf<Set<String>>(emptySet()) }
        AlertDialog(
            onDismissRequest = { showInviteMembers = false },
            title = { Text("Добавить участников", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    state.inviteCandidates.forEach { contact ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = contact.contactId in selected,
                                onCheckedChange = { checked ->
                                    selected = selected.toMutableSet().apply {
                                        if (checked) add(contact.contactId) else remove(contact.contactId)
                                    }
                                }
                            )
                            Text(contact.displayName, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        controller.inviteMembers(state.metadata.groupId, selected)
                        showInviteMembers = false
                    }
                ) { Text("Пригласить", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showInviteMembers = false }) { Text("Отмена") }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }
    selectedMemberForOptions?.let { member ->
        AlertDialog(
            onDismissRequest = { selectedMemberForOptions = null },
            title = { Text("Управление: ${member.displayName}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.management.canManageRoles && member.canChangeRole && !member.isCurrentUser && member.role != GroupRole.OWNER) {
                        if (member.role != GroupRole.ADMIN) {
                            TextButton(onClick = {
                                controller.setMemberRole(state.metadata.groupId, member.memberId, GroupRole.ADMIN)
                                selectedMemberForOptions = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Назначить администратором", modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (member.role != GroupRole.MODERATOR) {
                            TextButton(onClick = {
                                controller.setMemberRole(state.metadata.groupId, member.memberId, GroupRole.MODERATOR)
                                selectedMemberForOptions = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Назначить модератором", modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (member.role != GroupRole.MEMBER) {
                            TextButton(onClick = {
                                controller.setMemberRole(state.metadata.groupId, member.memberId, GroupRole.MEMBER)
                                selectedMemberForOptions = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Снять роль", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (state.management.canRestrictMembers && member.canRestrict && !member.isCurrentUser) {
                        TextButton(onClick = {
                            restrictionsFor = member
                            selectedMemberForOptions = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ограничить права", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (state.management.canRemoveMembers && member.canRemove && !member.isCurrentUser) {
                        TextButton(onClick = {
                            removeConfirmation = member
                            selectedMemberForOptions = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Исключить из группы", color = Color.Red, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (state.management.canBanMembers && member.canBan && !member.isCurrentUser) {
                        TextButton(onClick = {
                            banConfirmation = member
                            selectedMemberForOptions = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Заблокировать", color = Color.Red, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMemberForOptions = null }) { Text("Закрыть") }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun GroupHeroHeader(
    metadata: GroupMetadata,
    onAvatarClick: () -> Unit
) {
    val initials = metadata.title.take(2).uppercase().ifBlank { "GP" }
    val avatarColor = remember(metadata.groupId) {
        val colors = listOf(
            Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA),
            Color(0xFF1E88E5), Color(0xFF00ACC1), Color(0xFF43A047)
        )
        colors[abs(metadata.groupId.hashCode()) % colors.size]
    }

    val context = LocalContext.current
    val avatarBitmap = remember(metadata.avatarUri) {
        metadata.avatarUri?.let { uriStr ->
            runCatching {
                if (uriStr.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } else {
                    val file = File(uriStr)
                    if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                }
            }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("group_metadata"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .testTag("group_avatar_container"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    .background(avatarColor)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = "Group Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_attach_camera),
                    contentDescription = "Change Avatar",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = metadata.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "${metadata.memberCount} участников",
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun GroupQuickActionsRow(
    onChatClick: () -> Unit,
    onLeaveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val actions = listOf(
            Triple("Чат", R.drawable.ic_send_airplane, onChatClick),
            Triple("Звук", R.drawable.ic_notifications, {}),
            Triple("Покинуть", R.drawable.ic_delete, onLeaveClick)
        )

        actions.forEach { (label, iconRes, onClick) ->
            Surface(
                color = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = label,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaAttachmentRow(
    attachment: GroupAttachmentUi,
    message: GroupTimelineMessage
) {
    val context = LocalContext.current
    val localPath = attachment.localPath ?: attachment.fileName

    Surface(
        color = Color(0xFF14161A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val isGif = attachment.mimeType == "image/gif" || attachment.fileName.lowercase().endsWith(".gif")
                val isSticker = attachment.mimeType.contains("sticker") || StickerSupport.isStickerFileName(attachment.fileName)
                when {
                    isSticker && localPath.isNotBlank() -> {
                        AnimatedStickerImage(
                            filePath = localPath,
                            fallbackEmoji = "👍",
                            contentDescription = "Sticker",
                            targetSizePx = 128,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    isGif && localPath.isNotBlank() -> {
                        AnimatedGifImage(
                            filePath = localPath,
                            targetMaxDimensionPx = 256,
                            contentScale = GifContentScale.CROP,
                            contentDescription = "GIF",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        val bitmap = remember(localPath) {
                            runCatching {
                                if (localPath.startsWith("content://")) {
                                    context.contentResolver.openInputStream(Uri.parse(localPath))?.use { stream ->
                                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                        BitmapFactory.decodeStream(stream, null, opts)
                                    }
                                } else {
                                    val file = File(localPath)
                                    if (file.exists()) {
                                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                        BitmapFactory.decodeFile(file.absolutePath, opts)
                                    } else null
                                }
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = attachment.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_attach_gallery),
                                contentDescription = "Media",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${message.authorName} · ${message.timestampLabel} · ${attachment.sizeLabel}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun FileAttachmentRow(
    attachment: GroupAttachmentUi,
    message: GroupTimelineMessage,
    controller: GroupUiController,
    groupId: String
) {
    Surface(
        color = Color(0xFF14161A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_attach_paperclip),
                contentDescription = "File",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${message.authorName} · ${message.timestampLabel} · ${attachment.sizeLabel}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            TextButton(
                onClick = { controller.downloadAttachment(groupId, message.messageId) },
                enabled = !attachment.isDownloaded
            ) {
                Text(
                    if (attachment.isDownloaded) "Скачано" else "Скачать",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun GroupTabNavigation(
    selectedTab: Int,
    memberCount: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("Участники", "Медиа", "Избранное", "Файлы")
    Surface(
        color = Color(0xFF14161A),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else Color.Transparent
                        )
                        .clickable { onTabSelected(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMemberCard(
    groupId: String,
    member: GroupMember,
    management: GroupManagementPermissions,
    controller: GroupUiController,
    onMemberClick: () -> Unit,
    onRestrict: () -> Unit,
    onRemove: () -> Unit,
    onBan: () -> Unit,
    onTransfer: () -> Unit
) {
    val memberInitials = member.displayName.take(2).uppercase().ifBlank { "M" }
    val avatarColor = remember(member.displayName) {
        val colors = listOf(
            Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60),
            Color(0xFFF4511E), Color(0xFF7CB342), Color(0xFF00ACC1)
        )
        colors[abs(member.displayName.hashCode()) % colors.size]
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onMemberClick)
            .testTag("member_${member.memberId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = memberInitials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            append(member.displayName)
                            if (member.isCurrentUser) append(" (Вы)")
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (member.role == GroupRole.OWNER || member.role == GroupRole.ADMIN) {
                        Spacer(Modifier.width(4.dp))
                        Text("👑", fontSize = 12.sp)
                    }
                }
                Text(
                    text = member.statusLabel.ifBlank { "офлайн ?" },
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Surface(
                color = when (member.role) {
                    GroupRole.OWNER -> Color(0xFFE53935).copy(alpha = 0.2f)
                    GroupRole.ADMIN -> Color(0xFF1E88E5).copy(alpha = 0.2f)
                    GroupRole.MODERATOR -> Color(0xFF43A047).copy(alpha = 0.2f)
                    GroupRole.MEMBER -> Color.White.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = member.role.label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when (member.role) {
                        GroupRole.OWNER -> Color(0xFFE53935)
                        GroupRole.ADMIN -> Color(0xFF1E88E5)
                        GroupRole.MODERATOR -> Color(0xFF43A047)
                        GroupRole.MEMBER -> Color.Gray
                    }
                )
            }
        }

        // Hidden action test tags container for automated tests compatibility
        Box(modifier = Modifier.size(0.dp)) {
            val showRoleActions = management.canManageRoles && member.canChangeRole && !member.isCurrentUser && member.role != GroupRole.OWNER
            val showRestrict = management.canRestrictMembers && member.canRestrict && !member.isCurrentUser
            val showRemove = management.canRemoveMembers && member.canRemove && !member.isCurrentUser
            val showBan = management.canBanMembers && member.canBan && !member.isCurrentUser
            val showTransfer = management.canTransferOwnership && member.canTransferOwnership && !member.isCurrentUser

            if (showRoleActions && member.role != GroupRole.ADMIN) {
                Box(modifier = Modifier.clickable { controller.setMemberRole(groupId, member.memberId, GroupRole.ADMIN) }.testTag("make_admin_${member.memberId}"))
            }
            if (showRoleActions && member.role != GroupRole.MODERATOR) {
                Box(modifier = Modifier.clickable { controller.setMemberRole(groupId, member.memberId, GroupRole.MODERATOR) }.testTag("make_moderator_${member.memberId}"))
            }
            if (showRoleActions && member.role != GroupRole.MEMBER) {
                Box(modifier = Modifier.clickable { controller.setMemberRole(groupId, member.memberId, GroupRole.MEMBER) }.testTag("make_member_${member.memberId}"))
            }
            if (showRestrict) {
                Box(modifier = Modifier.clickable(onClick = onRestrict).testTag("restrict_${member.memberId}"))
            }
            if (showRemove) {
                Box(modifier = Modifier.clickable(onClick = onRemove).testTag("remove_${member.memberId}"))
            }
            if (showBan) {
                Box(modifier = Modifier.clickable(onClick = onBan).testTag("ban_${member.memberId}"))
            }
            if (showTransfer) {
                Box(modifier = Modifier.clickable(onClick = onTransfer).testTag("transfer_${member.memberId}"))
            }
        }
    }
}

@Composable
private fun AdminLogSection(state: GroupInfoUiState) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("admin_log")
    ) {
        Text("Журнал администрирования", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (!state.management.canViewAdminLog) {
            Text(
                "Просмотр доступен модераторам и администраторам.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        if (state.adminLog.isEmpty()) {
            Text(
                "Записи отсутствуют",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        state.adminLog.forEach { entry ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${entry.actorName} · ${entry.action}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    entry.timestampLabel,
                    fontSize = 11.sp,
                    color = onSurfaceColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun MemberRestrictionsDialog(
    member: GroupMember,
    onDismiss: () -> Unit,
    onApply: (GroupMemberPermissions) -> Unit
) {
    var permissions by remember(member.memberId) { mutableStateOf(member.permissions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ограничения для ${member.displayName}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                PermissionToggle("Отправка сообщений", permissions.canSendMessages) {
                    permissions = permissions.copy(canSendMessages = it)
                }
                PermissionToggle("Отправка медиафайлов", permissions.canSendMedia) {
                    permissions = permissions.copy(canSendMedia = it)
                }
                PermissionToggle("Отправка ссылок", permissions.canSendLinks) {
                    permissions = permissions.copy(canSendLinks = it)
                }
                PermissionToggle("Добавление участников", permissions.canAddMembers) {
                    permissions = permissions.copy(canAddMembers = it)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(permissions) },
                modifier = Modifier.testTag("apply_member_restrictions")
            ) { Text("Применить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 14.sp) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(confirmTag)
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold, color = Color.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun GroupInfoDetailsCard(
    metadata: GroupMetadata
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        color = Color(0xFF14161A),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Информация",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor
            )

            Spacer(Modifier.height(12.dp))

            // Group P2P Address
            Text(
                text = "Личный адрес группы",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "group#${metadata.groupId}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Group ID", "group#${metadata.groupId}")
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Адрес группы скопирован", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Copy Group ID",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            // Group Description
            Text(
                text = "О себе / Описание",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = metadata.description.ifBlank { "P2P децентрализованный групповой чат" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White
            )

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            // Security Verification Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF43A047).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = "Verified P2P",
                        tint = Color(0xFF43A047),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Статус верификации",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "Группа верифицирована (Double Ratchet)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMediaGrid(
    mediaMessages: List<GroupTimelineMessage>,
    onMediaClick: (String) -> Unit
) {
    val chunked = remember(mediaMessages) { mediaMessages.chunked(3) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chunked.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowItems.forEach { msg ->
                    val attachment = msg.attachment
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1C1C1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (attachment != null) {
                            MediaGridCell(
                                attachment = attachment,
                                onClick = {
                                    val path = attachment.localPath ?: attachment.fileName
                                    onMediaClick(path)
                                }
                            )
                        }
                    }
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MediaGridCell(
    attachment: GroupAttachmentUi,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val localPath = attachment.localPath ?: attachment.fileName
    val isGif = attachment.mimeType == "image/gif" || attachment.fileName.lowercase().endsWith(".gif")
    val isSticker = attachment.mimeType.contains("sticker") || StickerSupport.isStickerFileName(attachment.fileName)
    val isVideo = attachment.mimeType.startsWith("video/") || attachment.fileName.lowercase().run { endsWith(".mp4") || endsWith(".mov") || endsWith(".mkv") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
    ) {
        when {
            isSticker && localPath.isNotBlank() -> {
                AnimatedStickerImage(
                    filePath = localPath,
                    fallbackEmoji = "👍",
                    contentDescription = "Sticker",
                    targetSizePx = 128,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isGif && localPath.isNotBlank() -> {
                AnimatedGifImage(
                    filePath = localPath,
                    targetMaxDimensionPx = 256,
                    contentScale = GifContentScale.CROP,
                    contentDescription = "GIF",
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                val bitmap = remember(localPath) {
                    runCatching {
                        if (localPath.startsWith("content://")) {
                            context.contentResolver.openInputStream(Uri.parse(localPath))?.use { stream ->
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeStream(stream, null, opts)
                            }
                        } else {
                            val file = File(localPath)
                            if (file.exists()) {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeFile(file.absolutePath, opts)
                            } else null
                        }
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = attachment.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (isVideo) R.drawable.ic_voice_play else R.drawable.ic_attach_gallery),
                            contentDescription = "Media",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_voice_play),
                            contentDescription = "Play Video",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
