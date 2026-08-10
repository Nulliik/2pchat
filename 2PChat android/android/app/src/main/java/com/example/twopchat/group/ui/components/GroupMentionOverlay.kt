package com.example.twopchat.group.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.group.ui.GroupRole
import com.example.twopchat.group.ui.GroupMember

private val AvatarBgColors = listOf(
    Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8),
    Color(0xFF9575CD), Color(0xFF7986CB), Color(0xFF64B5F6),
    Color(0xFF4FC3F7), Color(0xFF4DB6AC), Color(0xFF81C784),
    Color(0xFFFFB74D), Color(0xFFFF8A65)
)

@Composable
internal fun GroupMentionSuggestionBar(
    suggestions: List<GroupMember>,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onMemberSelected: (GroupMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(suggestions, key = { member -> member.memberId }) { member ->
                    val avatarBitmap = remember(member.displayName, member.memberId) {
                        com.example.twopchat.P2PMessageRelay.peerAvatars[member.displayName]
                            ?: com.example.twopchat.P2PMessageRelay.getOriginalAvatar(context, member.displayName)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMemberSelected(member) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = member.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            val colorHash = member.displayName.hashCode()
                            val avatarBg = AvatarBgColors[Math.abs(colorHash) % AvatarBgColors.size]
                            val initial = remember(member.displayName) { member.displayName.take(1).uppercase() }

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(avatarBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initial,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = member.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )

                            if (member.role == GroupRole.OWNER || member.role == GroupRole.ADMIN) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (member.role == GroupRole.OWNER) "👑" else "⭐",
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = "@${member.displayName}",
                                fontSize = 14.sp,
                                color = onSurfaceColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}
