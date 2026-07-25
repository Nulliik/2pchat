
package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import com.example.twopchat.P2PPreferences
import com.example.twopchat.ConnectionTransportKind
import com.example.twopchat.connectionTransportKind
import org.json.JSONArray
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.twopchat.PythonBridge
import com.example.twopchat.Chat
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close


@Composable
fun TabNavigationRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    unreadCount: Int = 0,
    backgroundColor: Color = surfaceColor,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        containerColor = backgroundColor,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        val tabs = listOf(
            NavigationTabItem(Localizations.getString("tab_chats", appLanguage), com.example.twopchat.R.drawable.ic_menu_chats),
            NavigationTabItem(Localizations.getString("tab_contacts", appLanguage), com.example.twopchat.R.drawable.ic_menu_search),
            NavigationTabItem(Localizations.getString("tab_settings", appLanguage), com.example.twopchat.R.drawable.ic_menu_settings)
        )

        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            val iconScale by animateFloatAsState(
                targetValue = if (isSelected) 1.15f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "scale"
            )
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(index) },
                icon = {
                    Box {
                        Icon(
                            painter = painterResource(id = tab.iconRes),
                            contentDescription = tab.label,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                        if (index == 0 && unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 6.dp, y = (-2).dp)
                                    .background(primaryColor, shape = CircleShape)
                            )
                        }
                    }
                },
                label = {
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 0.2.sp
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = primaryColor,
                    selectedTextColor = primaryColor,
                    unselectedIconColor = onSurfaceColor.copy(alpha = 0.45f),
                    unselectedTextColor = onSurfaceColor.copy(alpha = 0.45f)
                )
            )
        }
    }
}

// Data models
data class PeerItem(
    val name: String,
    val lastMsg: String,
    val transport: String,
    val isDirect: Boolean,
    val initials: String,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isBlocked: Boolean = false,
    val hasDraft: Boolean = false
)

data class ContactItem(
    val name: String,
    val status: String,
    val initials: String,
    val verified: Boolean = true,
    val endpoints: String = "",
    val verificationDetails: String = "",
    val fingerprint: String = "",
    val ownershipVerified: Boolean = false,
)

data class AppIconOption(
    val alias: String,
    val name: String,
    val bg: Color,
    val fg: Color,
    val styleDesc: String,
    val fgRes: Int
)

data class NavigationTabItem(
    val label: String,
    val iconRes: Int
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PeerRow(
    peer: PeerItem,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sharedPrefs = remember(context) { P2PPreferences.prefs(context) }
    var isVerified by remember(peer.name) {
        mutableStateOf(sharedPrefs.getBoolean(P2PPreferences.verifiedPeer(peer.name), false))
    }
    DisposableEffect(sharedPrefs, peer.name) {
        val verificationKey = P2PPreferences.verifiedPeer(peer.name)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == verificationKey) {
                isVerified = prefs.getBoolean(verificationKey, false)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val isLight = surfaceColor.luminance() > 0.5f
    val borderAlpha = if (isLight) 0.08f else 0.04f

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1.0f,
        animationSpec = androidx.compose.animation.core.tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "peerRowScale"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isLight) 2.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .border(0.5.dp, onSurfaceColor.copy(alpha = borderAlpha), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Avatar representation with Online Status Dot
                Box(modifier = Modifier.size(46.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                    ) {
                        val avatarBitmap = com.example.twopchat.P2PMessageRelay.peerAvatars[peer.name]
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = "Avatar",
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else if (peer.initials == "🔖") {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_saved_messages),
                                contentDescription = "Saved Messages",
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text(
                                text = peer.initials,
                                color = primaryColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    if (peer.name != "Saved Messages") {
                        val isOnline = com.example.twopchat.P2PMessageRelay.peerSessionStates[peer.name] == true
                        if (isOnline) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(surfaceColor, shape = CircleShape)
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(primaryColor, shape = CircleShape)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (peer.hasDraft) {
                        val draftPrefix = if (appLanguage == "Русский") "Черновик: " else "Draft: "
                        val draftContent = peer.lastMsg.removePrefix(draftPrefix)
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        color = Color(0xFFE53935),
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(draftPrefix)
                                }
                                append(draftContent)
                            },
                            fontSize = 12.sp,
                            color = onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = peer.lastMsg,
                            fontSize = 12.sp,
                            color = if (peer.unreadCount > 0) onSurfaceColor else onSurfaceVariant,
                            fontWeight = if (peer.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            // Transport Badge (Quiet Luxury design)
            val transportKind = connectionTransportKind(peer.transport)
            val badgeBg = if (peer.isBlocked) {
                Color(0xFFFFEBEE)
            } else if (transportKind == ConnectionTransportKind.DIRECT) {
                primaryColor.copy(alpha = 0.1f)
            } else {
                onSurfaceColor.copy(alpha = 0.05f)
            }
            
            val badgeFg = if (peer.isBlocked) {
                Color(0xFFC62828)
            } else if (transportKind == ConnectionTransportKind.DIRECT) {
                primaryColor
            } else {
                onSurfaceVariant
            }
            
            val localizedTransport = if (peer.isBlocked) {
                if (appLanguage == "Русский") "ЗАБЛОКИРОВАН" else "BLOCKED"
            } else if (peer.transport == "LOCAL RAM") {
                Localizations.getString("local_storage", appLanguage)
            } else if (transportKind == ConnectionTransportKind.DIRECT) {
                Localizations.getString("direct_p2p", appLanguage)
            } else if (transportKind == ConnectionTransportKind.YGGDRASIL) {
                Localizations.getString("yggdrasil", appLanguage)
            } else {
                if (appLanguage == "Русский") "МАРШРУТ..." else "DETECTING..."
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (peer.isPinned) {
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_pin),
                        contentDescription = "Pinned",
                        tint = primaryColor,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(14.dp)
                    )
                }
                if (peer.unreadCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp)
                            .background(primaryColor, shape = CircleShape)
                    ) {
                        Text(
                            text = peer.unreadCount.toString(),
                            color = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(
                                    includeFontPadding = false
                                ),
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }

                if (peer.name != "Saved Messages") {
                    val isMismatch = sharedPrefs.getBoolean("fingerprint_mismatch_${peer.name}", false)
                    val shieldColor = when {
                        isMismatch -> Color(0xFFF44336) // Red
                        isVerified -> Color(0xFF4CAF50) // Green
                        else -> Color(0xFFFFC107) // Yellow
                    }
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_shield_status),
                        contentDescription = "Security Status",
                        tint = shieldColor,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(13.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .background(badgeBg, shape = RoundedCornerShape(8.dp))
                        .border(0.5.dp, badgeFg.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = localizedTransport,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }
    }
}
