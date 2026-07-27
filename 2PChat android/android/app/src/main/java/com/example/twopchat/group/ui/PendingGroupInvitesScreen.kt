package com.example.twopchat.group.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun PendingGroupInvitesScreen(
    state: PendingGroupInvitesUiState,
    controller: GroupUiController,
    modifier: Modifier = Modifier
) {
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onSurfaceColor
                    )
                }
                Text(
                    "Приглашения в группы",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            }
        }

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.testTag("invites_loading"),
                    color = primaryColor
                )
            }
            state.invites.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Нет входящих приглашений",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pending_group_invites"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val initials = invite.groupTitle.take(2).uppercase().ifBlank { "GP" }
    val avatarColor = remember(invite.groupTitle) {
        val colors = listOf(
            Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
            Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00ACC1)
        )
        colors[abs(invite.groupTitle.hashCode()) % colors.size]
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("invite_${invite.inviteId}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.05f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(invite.groupTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Приглашение от ${invite.inviterName}", fontSize = 12.sp, color = primaryColor, fontWeight = FontWeight.Medium)
                }
            }

            if (invite.groupDescription.isNotBlank()) {
                Text(
                    invite.groupDescription,
                    fontSize = 13.sp,
                    color = onSurfaceColor.copy(alpha = 0.7f)
                )
            }

            Text(
                "${invite.memberCount} участников" +
                    if (invite.receivedAtLabel.isNotBlank()) " · ${invite.receivedAtLabel}" else "",
                fontSize = 11.sp,
                color = onSurfaceColor.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { controller.declineInvite(invite.inviteId) },
                    enabled = !invite.isProcessing,
                    modifier = Modifier.testTag("decline_${invite.inviteId}"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Отклонить", fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { controller.acceptInvite(invite.inviteId) },
                    enabled = !invite.isProcessing,
                    modifier = Modifier.testTag("accept_${invite.inviteId}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (invite.isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Принять", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
