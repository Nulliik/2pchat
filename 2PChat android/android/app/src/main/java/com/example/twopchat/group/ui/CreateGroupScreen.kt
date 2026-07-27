package com.example.twopchat.group.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CreateGroupScreen(
  state: CreateGroupUiState,
  controller: GroupUiController,
  modifier: Modifier = Modifier
) {
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
    selectedContactIds.isNotEmpty() &&
    !state.isCreating

  Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding)
        .padding(horizontal = 16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(
          onClick = controller::onBack,
          modifier = Modifier.testTag("create_group_back")
        ) {
          Text("Back")
        }
        Text(
          text = "New group",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold
        )
      }

      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("group_title_input"),
        label = { Text("Group title") },
        supportingText = {
          if (cleanTitle.isEmpty() && title.isNotEmpty()) Text("Title cannot be blank")
        },
        isError = cleanTitle.isEmpty() && title.isNotEmpty(),
        singleLine = true
      )
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("group_description_input"),
        label = { Text("Description (optional)") },
        minLines = 2,
        maxLines = 4
      )
      Spacer(Modifier.height(16.dp))
      Text(
        text = "Add members · ${selectedContactIds.size} selected",
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        text = "Select at least one known contact. You will become the owner.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .testTag("known_contacts_list")
      ) {
        if (state.knownContacts.isEmpty()) {
          item {
            Text(
              text = "No known contacts yet",
              modifier = Modifier.padding(vertical = 24.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
        items(state.knownContacts, key = GroupContactSummary::contactId) { contact ->
          val selected = contact.contactId in selectedContactIds
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable(enabled = !state.isCreating) {
                selectedContactIds = selectedContactIds.toMutableSet().apply {
                  if (selected) remove(contact.contactId) else add(contact.contactId)
                }
              }
              .testTag("contact_${contact.contactId}")
              .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Checkbox(
              checked = selected,
              onCheckedChange = null,
              enabled = !state.isCreating
            )
            Column(Modifier.weight(1f)) {
              Text(contact.displayName, fontWeight = FontWeight.Medium)
              val detail = contact.secondaryText.ifBlank {
                if (contact.isOnline) "Online" else "Offline"
              }
              Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
          HorizontalDivider()
        }
      }

      state.errorMessage?.let {
        Text(
          text = it,
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("create_group_error"),
          color = MaterialTheme.colorScheme.error
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
          .padding(vertical = 12.dp)
          .testTag("create_group_button")
      ) {
        if (state.isCreating) {
          CircularProgressIndicator(
            modifier = Modifier.height(20.dp),
            strokeWidth = 2.dp
          )
        } else {
          Text("Create group")
        }
      }
    }
  }
}
