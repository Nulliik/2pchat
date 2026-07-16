
package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import org.json.JSONArray
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
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
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    useAmoled: Boolean,
    onAmoledChanged: (Boolean) -> Unit,
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var localFingerprint by remember { mutableStateOf("Loading...") }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showRadarView by remember { mutableStateOf(true) }
    var selectedRadarNode by remember { mutableStateOf<RadarNode?>(null) }
    LaunchedEffect(Unit) {
        while (!PythonBridge.isInitialized) {
            kotlinx.coroutines.delay(100)
        }
        localFingerprint = withContext(Dispatchers.IO) { PythonBridge.getLocalFingerprint() }
    }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var activeIconAlias by remember { mutableStateOf(sharedPrefs.getString("active_icon_alias", "MainActivityAliasDefault") ?: "MainActivityAliasDefault") }

    var mainActiveChatsSet by remember {
        mutableStateOf(sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet())
    }
    var totalUnreadCount by remember {
        mutableStateOf(mainActiveChatsSet.sumOf { sharedPrefs.getInt("unread_count_$it", 0) })
    }

    DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active_chats" || key?.startsWith("unread_count_") == true) {
                val chats = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                mainActiveChatsSet = chats
                totalUnreadCount = chats.sumOf { sharedPrefs.getInt("unread_count_$it", 0) }
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    var activePeers by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            if (PythonBridge.isInitialized) {
                activePeers = PythonBridge.getActivePeers()
            }
            kotlinx.coroutines.delay(2500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2PChat",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = primaryColor,
                letterSpacing = (-0.5).sp
            )

            // P2P Active Status Chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(surfaceColor, shape = RoundedCornerShape(12.dp))
                    .border(
                        0.5.dp, 
                        if (activePeers.isNotEmpty()) Color(0xFF4CAF50).copy(alpha = 0.5f) else primaryColor.copy(alpha = 0.3f), 
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = activePeers.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            (if (appLanguage == "Русский") "Активные сессии: " else "Active sessions: ") + activePeers.joinToString(", "),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (activePeers.isNotEmpty()) Color(0xFF4CAF50) else onSurfaceColor.copy(alpha = 0.3f), shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (activePeers.isNotEmpty()) {
                        if (activePeers.size == 1) {
                            activePeers.first()
                        } else {
                            "${activePeers.size} " + (if (appLanguage == "Русский") "актив." else "active")
                        }
                    } else {
                        if (appLanguage == "Русский") "нет сессий" else "no sessions"
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (activePeers.isNotEmpty()) Color(0xFF4CAF50) else onSurfaceColor.copy(alpha = 0.5f),
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 85.dp)
                )
            }
        }

        // Tab Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)).togetherWith(fadeOut(animationSpec = tween(220)))
                },
                label = "tab_transition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> ChatsTab(
                        onItemClick = onItemClick,
                        localFingerprint = localFingerprint,
                        appLanguage = appLanguage,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        onStatusPillClick = { node ->
                            selectedRadarNode = node
                            showRadarView = true
                            showLogsDialog = true
                        }
                    )
                    1 -> ContactsTab(onItemClick, appLanguage, primaryColor, surfaceColor, onSurfaceColor, onSurfaceVariant)
                    2 -> SettingsTab(
                        isDarkTheme = isDarkTheme,
                        onThemeChanged = onThemeChanged,
                        useCerulean = useCerulean,
                        onAccentChanged = onAccentChanged,
                        useAmoled = useAmoled,
                        onAmoledChanged = onAmoledChanged,
                        activeIconAlias = activeIconAlias,
                        onIconChanged = { alias ->
                            activeIconAlias = alias
                            sharedPrefs.edit().putString("active_icon_alias", alias).apply()
                            val isDisguised = sharedPrefs.getBoolean("settings_stealth_disguise", false)
                            if (!isDisguised) {
                                onIconChanged(alias)
                            }
                        },
                        appLanguage = appLanguage,
                        onLanguageChanged = onLanguageChanged,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        surfaceVariant = surfaceVariant,
                        onDeleteAccount = onDeleteAccount,
                        onShowLogs = { showLogsDialog = true }
                    )
                }
            }
        }

        // Bottom Navigation Bar
        TabNavigationRow(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            unreadCount = totalUnreadCount

        )
    }

    NetworkDiagnosticsDialog(
        showLogsDialog = showLogsDialog,
        onDismissRequest = { showLogsDialog = false },
        showRadarView = showRadarView,
        onShowRadarViewChange = { showRadarView = it },
        selectedRadarNode = selectedRadarNode,
        onSelectedRadarNodeChange = { selectedRadarNode = it },
        appLanguage = appLanguage,
        primaryColor = primaryColor,
        surfaceColor = surfaceColor,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        surfaceVariant = surfaceVariant,
        sharedPrefs = sharedPrefs
    )
}

// ================= Chats Tab Screen =================
