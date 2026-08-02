package com.example.twopchat.group.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs

@Composable
fun PendingGroupInvitesScreen(
    state: PendingGroupInvitesUiState,
    controller: GroupUiController,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary

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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onSurfaceColor
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Приглашения в группы",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
                if (state.invites.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${state.invites.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.testTag("invites_loading"),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            state.invites.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                color = primaryColor.copy(alpha = 0.12f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_incoming_invite),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Нет входящих приглашений",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Когда вас пригласят в новую группу, приглашение появится здесь.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pending_group_invites"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.invites, key = PendingGroupInvite::inviteId) { invite ->
                    InviteCard(invite, controller)
                }
            }
        }
    }
}

@Composable
private fun InviteCard(invite: PendingGroupInvite, controller: GroupUiController) {
    val context = LocalContext.current

    val initials = invite.groupTitle.take(2).uppercase().ifBlank { "GP" }
    val inviterInitials = invite.inviterName.take(2).uppercase().ifBlank { "U" }

    val avatarColor = remember(invite.groupTitle) {
        val colors = listOf(
            Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60),
            Color(0xFFF4511E), Color(0xFF7CB342), Color(0xFF00ACC1)
        )
        colors[abs(invite.groupTitle.hashCode()) % colors.size]
    }

    val inviterAvatarColor = remember(invite.inviterName) {
        val colors = listOf(
            Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
            Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00ACC1)
        )
        colors[abs(invite.inviterName.hashCode()) % colors.size]
    }

    val inviterAvatarBitmap = remember(invite.inviterName) {
        com.example.twopchat.P2PMessageRelay.peerAvatars[invite.inviterName]
    }
    val groupAvatarBitmap = remember(invite.groupAvatarUri, invite.groupTitle) {
        val cached = com.example.twopchat.P2PMessageRelay.peerAvatars[invite.groupTitle]
        if (cached != null) {
            cached
        } else {
            invite.groupAvatarUri?.let { uriStr ->
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
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("invite_${invite.inviteId}"),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF181B22),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Group Avatar with Inviter Badge
                Box(modifier = Modifier.size(56.dp)) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (groupAvatarBitmap != null) {
                            Image(
                                bitmap = groupAvatarBitmap.asImageBitmap(),
                                contentDescription = invite.groupTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else if (inviterAvatarBitmap != null && invite.groupAvatarUri == null) {
                            Image(
                                bitmap = inviterAvatarBitmap.asImageBitmap(),
                                contentDescription = invite.groupTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = initials,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }

                    // Overlapping Inviter Avatar Badge
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .border(1.5.dp, Color(0xFF181B22), CircleShape)
                            .background(inviterAvatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (inviterAvatarBitmap != null) {
                            Image(
                                bitmap = inviterAvatarBitmap.asImageBitmap(),
                                contentDescription = invite.inviterName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = inviterInitials.take(1),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = invite.groupTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Приглашение от ${invite.inviterName}",
                            fontSize = 12.sp,
                            color = Color(0xFF34D399),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (invite.groupDescription.isNotBlank()) {
                Surface(
                    color = Color.White.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = invite.groupDescription,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "👥 ${invite.memberCount} участников",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    if (invite.receivedAtLabel.isNotBlank()) {
                        Text(
                            text = " · ${invite.receivedAtLabel}",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { controller.declineInvite(invite.inviteId) },
                    enabled = !invite.isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("decline_${invite.inviteId}"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
                ) {
                    Text("Отклонить", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = { controller.acceptInvite(invite.inviteId) },
                    enabled = !invite.isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("accept_${invite.inviteId}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981)
                    )
                ) {
                    if (invite.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text("Принять", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
