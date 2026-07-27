package com.example.twopchat.group.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PendingGroupInvitesScreen(
  state: PendingGroupInvitesUiState,
  controller: GroupUiController,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.fillMaxSize()) {
    Surface(tonalElevation = 2.dp) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(onClick = controller::onBack) { Text("Back") }
        Text(
          "Group invitations",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold
        )
      }
    }

    when {
      state.isLoading -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator(modifier = Modifier.testTag("invites_loading"))
      }
      state.invites.isEmpty() -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "No pending invitations",
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      else -> LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .testTag("pending_group_invites"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
  OutlinedCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("invite_${invite.inviteId}")
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(invite.groupTitle, style = MaterialTheme.typography.titleMedium)
      if (invite.groupDescription.isNotBlank()) {
        Text(invite.groupDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text("Invited by ${invite.inviterName}")
      Text(
        "${invite.memberCount} members" +
          if (invite.receivedAtLabel.isNotBlank()) " · ${invite.receivedAtLabel}" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
      ) {
        OutlinedButton(
          onClick = { controller.declineInvite(invite.inviteId) },
          enabled = !invite.isProcessing,
          modifier = Modifier.testTag("decline_${invite.inviteId}")
        ) {
          Text("Decline")
        }
        Button(
          onClick = { controller.acceptInvite(invite.inviteId) },
          enabled = !invite.isProcessing,
          modifier = Modifier
            .padding(start = 8.dp)
            .testTag("accept_${invite.inviteId}")
        ) {
          if (invite.isProcessing) CircularProgressIndicator(strokeWidth = 2.dp)
          else Text("Accept")
        }
      }
    }
  }
}
