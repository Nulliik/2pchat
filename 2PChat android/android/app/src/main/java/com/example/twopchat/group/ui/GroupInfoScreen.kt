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
    var restrictionsFor by remember { mutableStateOf<GroupMember?>(null) }
    var removeConfirmation by remember { mutableStateOf<GroupMember?>(null) }
    var banConfirmation by remember { mutableStateOf<GroupMember?>(null) }
    var transferConfirmation by remember { mutableStateOf<GroupMember?>(null) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    var showEditMetadata by remember { mutableStateOf(false) }
    var showInviteMembers by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
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
                IconButton(onClick = controller::onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back_arrow),
                        contentDescription = "Back",
                        tint = onSurfaceColor
                    )
                }
                Text(
                    "Информация о группе",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("group_info_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "metadata") {
                GroupMetadataCard(state.metadata, state.currentUserRole)
            }

            if (state.management.canEditMetadata || state.management.canInviteMembers) {
                item(key = "management_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.management.canEditMetadata) {
                            Button(
                                onClick = { showEditMetadata = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("edit_group_info"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                            ) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Изменить", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (state.management.canInviteMembers && state.inviteCandidates.isNotEmpty()) {
                            Button(
                                onClick = { showInviteMembers = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("invite_group_members"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                            ) {
                                Icon(painterResource(R.drawable.ic_add_square), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Добавить", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item(key = "members_header") {
                Text(
                    "Участники · ${state.members.size}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }

            items(state.members, key = GroupMember::memberId) { member ->
                GroupMemberCard(
                    groupId = state.metadata.groupId,
                    member = member,
                    management = state.management,
                    controller = controller,
                    onRestrict = { restrictionsFor = member },
                    onRemove = { removeConfirmation = member },
                    onBan = { banConfirmation = member },
                    onTransfer = { transferConfirmation = member }
                )
            }

            item(key = "admin_log") {
                AdminLogSection(state)
            }

            if (state.management.canLeave) {
                item(key = "leave") {
                    Button(
                        onClick = { showLeaveConfirmation = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(46.dp)
                            .testTag("leave_group_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text(
                            if (state.currentUserRole == GroupRole.OWNER) "Покинуть или передать группу" else "Выйти из группы",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
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
}

@Composable
private fun GroupMetadataCard(metadata: GroupMetadata, currentUserRole: GroupRole) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val initials = metadata.title.take(2).uppercase().ifBlank { "GP" }
    val avatarColor = remember(metadata.groupId) {
        val colors = listOf(
            Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
            Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00ACC1)
        )
        colors[abs(metadata.groupId.hashCode()) % colors.size]
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("group_metadata"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(metadata.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            if (metadata.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    metadata.description,
                    fontSize = 13.sp,
                    color = onSurfaceColor.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        "${metadata.memberCount} участников",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        "Роль: ${currentUserRole.label}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (metadata.createdByLabel.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Создатель: ${metadata.createdByLabel} · ${metadata.createdAtLabel}",
                    fontSize = 11.sp,
                    color = onSurfaceColor.copy(alpha = 0.5f)
                )
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
    onRestrict: () -> Unit,
    onRemove: () -> Unit,
    onBan: () -> Unit,
    onTransfer: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val memberInitials = member.displayName.take(2).uppercase().ifBlank { "M" }
    val avatarColor = remember(member.displayName) {
        val colors = listOf(
            Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60),
            Color(0xFFF4511E), Color(0xFF7CB342), Color(0xFF00ACC1)
        )
        colors[abs(member.displayName.hashCode()) % colors.size]
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .testTag("member_${member.memberId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = memberInitials,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(member.displayName)
                            if (member.isCurrentUser) append(" (Вы)")
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${member.role.label} · ${member.statusLabel}",
                        fontSize = 11.sp,
                        color = onSurfaceColor.copy(alpha = 0.6f)
                    )
                }

                // Member Role Badge
                Surface(
                    color = when (member.role) {
                        GroupRole.OWNER -> Color(0xFFE53935)
                        GroupRole.ADMIN -> Color(0xFF1E88E5)
                        GroupRole.MODERATOR -> Color(0xFF43A047)
                        GroupRole.MEMBER -> primaryColor.copy(alpha = 0.12f)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        member.role.label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (member.role == GroupRole.MEMBER) primaryColor else Color.White
                    )
                }
            }

            if (!member.permissions.canSendMessages) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Только чтение",
                    fontSize = 11.sp,
                    color = Color.Red,
                    fontWeight = FontWeight.Bold
                )
            }

            val showRoleActions = management.canManageRoles &&
                member.canChangeRole &&
                !member.isCurrentUser &&
                member.role != GroupRole.OWNER
            val showRestrict = management.canRestrictMembers && member.canRestrict && !member.isCurrentUser
            val showRemove = management.canRemoveMembers && member.canRemove && !member.isCurrentUser
            val showBan = management.canBanMembers && member.canBan && !member.isCurrentUser
            val showTransfer = management.canTransferOwnership &&
                member.canTransferOwnership &&
                !member.isCurrentUser

            if (showRoleActions || showRestrict || showRemove || showBan || showTransfer) {
                HorizontalDivider(Modifier.padding(top = 8.dp), color = primaryColor.copy(alpha = 0.1f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showRoleActions && member.role != GroupRole.ADMIN) {
                        TextButton(
                            onClick = { controller.setMemberRole(groupId, member.memberId, GroupRole.ADMIN) },
                            modifier = Modifier.testTag("make_admin_${member.memberId}")
                        ) { Text("Сделать админом", fontSize = 11.sp) }
                    }
                    if (showRoleActions && member.role != GroupRole.MODERATOR) {
                        TextButton(
                            onClick = { controller.setMemberRole(groupId, member.memberId, GroupRole.MODERATOR) },
                            modifier = Modifier.testTag("make_moderator_${member.memberId}")
                        ) { Text("Сделать модератором", fontSize = 11.sp) }
                    }
                    if (showRoleActions && member.role != GroupRole.MEMBER) {
                        TextButton(
                            onClick = { controller.setMemberRole(groupId, member.memberId, GroupRole.MEMBER) },
                            modifier = Modifier.testTag("make_member_${member.memberId}")
                        ) { Text("Снять роль", fontSize = 11.sp) }
                    }
                    if (showRestrict) {
                        TextButton(
                            onClick = onRestrict,
                            modifier = Modifier.testTag("restrict_${member.memberId}")
                        ) { Text("Ограничить", fontSize = 11.sp) }
                    }
                    if (showRemove) {
                        TextButton(
                            onClick = onRemove,
                            modifier = Modifier.testTag("remove_${member.memberId}")
                        ) { Text("Удалить", fontSize = 11.sp, color = Color.Red) }
                    }
                    if (showBan) {
                        TextButton(
                            onClick = onBan,
                            modifier = Modifier.testTag("ban_${member.memberId}")
                        ) { Text("Забанить", fontSize = 11.sp, color = Color.Red) }
                    }
                    if (showTransfer) {
                        TextButton(
                            onClick = onTransfer,
                            modifier = Modifier.testTag("transfer_${member.memberId}")
                        ) { Text("Передать владение", fontSize = 11.sp) }
                    }
                }
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
