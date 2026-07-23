
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
import com.example.twopchat.P2PPreferences
import com.example.twopchat.P2PRelayService
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
import androidx.activity.compose.BackHandler

private fun calculateDirSize(file: java.io.File?): Long {
    if (file == null || !file.exists()) return 0L
    if (file.isFile) return file.length()
    var total = 0L
    val children = file.listFiles() ?: return 0L
    for (child in children) {
        total += calculateDirSize(child)
    }
    return total
}

private fun deleteDirContents(file: java.io.File?, keepDir: Boolean = true) {
    if (file == null || !file.exists()) return
    if (file.isDirectory) {
        val children = file.listFiles() ?: return
        for (child in children) {
            deleteDirContents(child, keepDir = false)
        }
    }
    if (!keepDir) {
        file.delete()
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun SettingsTab(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    useAmoled: Boolean,
    onAmoledChanged: (Boolean) -> Unit,
    activeIconAlias: String,
    onIconChanged: (String) -> Unit,
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    surfaceVariant: Color,
    onDeleteAccount: () -> Unit,
    onShowLogs: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val sharedPrefs = remember { com.example.twopchat.P2PPreferences.prefs(context) }
    
    // Profile photo states
    var profilePhotoUri by remember { mutableStateOf(sharedPrefs.getString("profile_photo_uri", null)) }
    var profileBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri)) }
    var pendingCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAvatarOptions by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            pendingCropUri = it
        }
    }

    // Dynamic settings states
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var aboutMeText by remember { mutableStateOf(sharedPrefs.getString("about_me_profile", "") ?: "") }
    var showEditAboutMeDialog by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_notifications", true)) }
    var previewsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_previews", false)) }
    var blockScreenshots by remember { mutableStateOf(sharedPrefs.getBoolean("settings_screenshots", true)) }
    var passcodeLock by remember { mutableStateOf(sharedPrefs.getBoolean("settings_passcode", false)) }
    var wifiDiscovery by remember { mutableStateOf(sharedPrefs.getBoolean("settings_wifi", true)) }
    var listenerPortText by remember {
        mutableStateOf(P2PPreferences.listenerPort(context).toString())
    }
    var yggdrasilRouting by remember { mutableStateOf(sharedPrefs.getBoolean("settings_yggdrasil", true)) }
    var ipv4Routing by remember { mutableStateOf(sharedPrefs.getBoolean("settings_ipv4", true)) }
    var persistChatHistory by remember { mutableStateOf(sharedPrefs.getBoolean("persist_chat_history", true)) }
    var linkPreviewsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_link_previews", false)) }
    var hapticFeedbackEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_haptic_feedback", true)) }
    var reduceMotion by remember { mutableStateOf(sharedPrefs.getBoolean(com.example.twopchat.REDUCE_MOTION_SETTING, false)) }
    var stealthDisguise by remember { mutableStateOf(sharedPrefs.getBoolean("settings_stealth_disguise", false)) }
    var showDisguiseInstructionDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        yggdrasilRouting = sharedPrefs.getBoolean("settings_yggdrasil", true)
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                    action = PacketTunnelProvider.ACTION_START
                }
                context.startService(intent)
                yggdrasilRouting = true
                sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
            } else {
                yggdrasilRouting = false
                sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
            }
        }
    )
    
    var showLanguageDialog by remember { mutableStateOf(false) }
    
    // Passcode dialog flow states
    var showSetPasscodeDialog by remember { mutableStateOf(false) }
    var showDisablePasscodeDialog by remember { mutableStateOf(false) }
    var showAutolockDialog by remember { mutableStateOf(false) }
    var autolockMinutes by remember { mutableStateOf(sharedPrefs.getInt("passcode_autolock_minutes", 1)) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showSetDuressDialog by remember { mutableStateOf(false) }
    var showLauncherIconsPicker by remember { mutableStateOf(false) }
    var showThemesPicker by remember { mutableStateOf(false) }
    var showRegenerateYggdrasilKeysDialog by remember { mutableStateOf(false) }

    if (showEditAboutMeDialog) {
        var tempText by remember { mutableStateOf(aboutMeText) }
        AlertDialog(
            onDismissRequest = { showEditAboutMeDialog = false },
            title = { Text(if (appLanguage == "Русский") "О себе" else "About Me") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempText,
                        onValueChange = {
                            if (it.length <= 70) {
                                tempText = it
                            }
                        },
                        placeholder = {
                            Text(if (appLanguage == "Русский") "Расскажите немного о себе..." else "Tell something about yourself...")
                        },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${tempText.length} / 70",
                        fontSize = 11.sp,
                        color = onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        aboutMeText = tempText.trim()
                        sharedPrefs.edit().putString("about_me_profile", aboutMeText).apply()
                        showEditAboutMeDialog = false
                        
                        // Update Python identity & announce
                        val localFingerprint = PythonBridge.getLocalFingerprint()
                        PythonBridge.configureLocalIdentity(username, localFingerprint, aboutMeText)
                        P2PMessageRelay.refreshAnnouncement(context)
                    }
                ) {
                    Text(if (appLanguage == "Русский") "Сохранить" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditAboutMeDialog = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            }
        )
    }

    if (showRegenerateYggdrasilKeysDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateYggdrasilKeysDialog = false },
            title = {
                Text(if (appLanguage == "Русский") "Сгенерировать новый ключ Yggdrasil?" else "Generate a new Yggdrasil key?")
            },
            text = {
                Text(
                    if (appLanguage == "Русский") {
                        "Текущий Yggdrasil IPv6 изменится. Сохранённые у контактов старые адреса перестанут работать."
                    } else {
                        "Your Yggdrasil IPv6 address will change. Contacts with the old address will no longer be able to reach you."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startService(Intent(context, PacketTunnelProvider::class.java).apply {
                        action = PacketTunnelProvider.ACTION_REGENERATE_KEYS
                    })
                    showRegenerateYggdrasilKeysDialog = false
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "Yggdrasil-ключ обновлён" else "Yggdrasil key regenerated",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(if (appLanguage == "Русский") "Сгенерировать" else "Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateYggdrasilKeysDialog = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            },
        )
    }

    if (pendingCropUri != null) {
        com.example.twopchat.ui.onboarding.ImageCropper(
            imageUri = pendingCropUri!!,
            onCropSuccess = { localPath ->
                profilePhotoUri = localPath
                sharedPrefs.edit().putString("profile_photo_uri", localPath).apply()
                profileBitmap = com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, localPath)
                com.example.twopchat.P2PMessageRelay.shareAvatarWithConnectedPeers(context)
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
                pendingCropUri = null
            },
            onCancel = {
                pendingCropUri = null
            },
            appLanguage = appLanguage
        )
        return
    }

    var activeSubPage by remember { mutableStateOf<String?>(null) }

    if (activeSubPage != null) {
        BackHandler {
            activeSubPage = null
        }
    }

    AnimatedContent(
        targetState = activeSubPage,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { width -> width } + fadeIn() togetherWith
                        slideOutHorizontally { width -> -width } + fadeOut()
            } else {
                slideInHorizontally { width -> -width } + fadeIn() togetherWith
                        slideOutHorizontally { width -> width } + fadeOut()
            }
        },
        label = "SettingsSubPageNavigation"
    ) { subPage ->
        when (subPage) {
            null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Visual Profile Card with interactive photo selector
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Profile Photo container (clickable)
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clickable {
                                        if (profileBitmap != null) {
                                            showAvatarOptions = true
                                        } else {
                                            imagePickerLauncher.launch("image/*")
                                        }
                                    }
                            ) {
                                // Avatar circle
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(primaryColor.copy(alpha = 0.15f), shape = CircleShape)
                                        .border(1.dp, primaryColor, CircleShape)
                                ) {
                                    if (profileBitmap != null) {
                                        Image(
                                            bitmap = profileBitmap!!.asImageBitmap(),
                                            contentDescription = "Profile Photo",
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_add_photo_smiley),
                                            contentDescription = "No Profile Photo",
                                            tint = primaryColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                // Camera badge — bottom-right corner
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .align(Alignment.BottomEnd)
                                        .background(primaryColor, shape = CircleShape)
                                        .border(1.5.dp, surfaceColor, CircleShape)
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_attach_camera),
                                        contentDescription = "Change photo",
                                        tint = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = username,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = onSurfaceColor
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Your Identity",
                                    fontSize = 12.sp,
                                    color = onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { showEditAboutMeDialog = true }
                                        .padding(vertical = 2.dp)
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_edit),
                                        contentDescription = "Edit bio",
                                        tint = primaryColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (aboutMeText.isEmpty()) {
                                            if (appLanguage == "Русский") "О себе: Нажмите, чтобы добавить..." else "About me: Tap to add..."
                                        } else {
                                            if (appLanguage == "Русский") "О себе: $aboutMeText" else "About me: $aboutMeText"
                                        },
                                        fontSize = 13.sp,
                                        color = if (aboutMeText.isEmpty()) onSurfaceVariant.copy(alpha = 0.6f) else onSurfaceColor.copy(alpha = 0.8f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Categories Group Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    ) {
                        Column {
                            // Category: Chat Settings / Оформление
                            SettingsRow(
                                title = if (appLanguage == "Русский") "Настройки чатов и Оформление" else "Chat Settings & Theme",
                                subtitle = if (appLanguage == "Русский") "Тема, цвет акцента, иконка приложения" else "Theme, accent color, launcher icon",
                                iconRes = com.example.twopchat.R.drawable.ic_menu_chats,
                                iconColor = Color(0xFFF5B041),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "chat_settings" }
                            )
                            
                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))
                            
                            // Category: Privacy & Security / Конфиденциальность
                            SettingsRow(
                                title = if (appLanguage == "Русский") "Конфиденциальность и Сеть" else "Privacy & Security",
                                subtitle = if (appLanguage == "Русский") "Код-пароль, скриншоты, порты и маршруты" else "Passcode, screenshots, ports and routes",
                                iconRes = com.example.twopchat.R.drawable.ic_shield_status,
                                iconColor = Color(0xFF4CAF50),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "security" }
                            )
                            
                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                            SettingsRow(
                                title = if (appLanguage == "Русский") "Трекеры и обнаружение" else "Trackers & Discovery",
                                subtitle = if (appLanguage == "Русский") "Типы, announce и пользовательские трекеры" else "Types, announces and custom trackers",
                                iconRes = com.example.twopchat.R.drawable.ic_menu_search,
                                iconColor = Color(0xFF29B6F6),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "trackers" }
                            )

                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                            SettingsRow(
                                title = if (appLanguage == "Русский") "Пиры Yggdrasil" else "Yggdrasil Peers",
                                subtitle = if (appLanguage == "Русский") {
                                    "Публичные и пользовательские пиры, включение и сортировка"
                                } else {
                                    "Public and custom peers, toggles and sorting"
                                },
                                iconRes = com.example.twopchat.R.drawable.ic_quick_link,
                                iconColor = Color(0xFF7E57C2),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "yggdrasil_peers" }
                            )

                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))
                            
                            // Category: Notifications / Уведомления
                            SettingsRow(
                                title = if (appLanguage == "Русский") "Уведомления и звуки" else "Notifications",
                                subtitle = if (appLanguage == "Русский") "Включение уведомлений, превью сообщений" else "Toggles, previews",
                                iconRes = com.example.twopchat.R.drawable.ic_notifications,
                                iconColor = Color(0xFFE57373),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "notifications" }
                            )
                            
                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                            // Category: Data & Storage / Данные и память
                            SettingsRow(
                                title = if (appLanguage == "Русский") "Данные и память" else "Data & Storage",
                                subtitle = if (appLanguage == "Русский") "Использование памяти и очистка кэша" else "Storage usage & cache cleanup",
                                iconRes = com.example.twopchat.R.drawable.ic_database_storage,
                                iconColor = Color(0xFF66BB6A),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "storage" }
                            )

                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))
                            
                            // Category: Language / Язык
                            SettingsRow(
                                title = Localizations.getString("language", appLanguage),
                                subtitle = if (appLanguage == "Русский") "Выбор языка приложения" else "Choose app language",
                                value = appLanguage,
                                iconRes = com.example.twopchat.R.drawable.ic_quick_link,
                                iconColor = Color(0xFF29B6F6),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { showLanguageDialog = true }
                            )

                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                            // Category: Help & Reference / Справка
                            SettingsRow(
                                title = Localizations.getString("help_reference", appLanguage),
                                subtitle = Localizations.getString("help_reference_desc", appLanguage),
                                iconRes = com.example.twopchat.R.drawable.ic_help_question,
                                iconColor = Color(0xFFAB47BC),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { activeSubPage = "help_reference" }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // System Group Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    ) {
                        Column {
                            // Network Diagnostics & Logs
                            SettingsRow(
                                title = if (appLanguage == "Русский") "Сетевой отладчик и Логи" else "Network Diagnostics & Logs",
                                iconRes = com.example.twopchat.R.drawable.ic_menu_settings,
                                iconColor = Color(0xFF78909C),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = { onShowLogs() }
                            )
                            
                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))
                            
                            // Export Logs
                            SettingsRow(
                                title = if (appLanguage == "Русский") "Экспорт логов приложения" else "Export App Logs",
                                iconRes = com.example.twopchat.R.drawable.ic_quick_ip,
                                iconColor = Color(0xFF8D6E63),
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                onClick = {
                                    val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
                                    if (logFile.exists() && logFile.length() > 0) {
                                        try {
                                            val authority = "${context.packageName}.fileprovider"
                                            val fileUri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(context, authority, logFile)
                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(shareIntent, if (appLanguage == "Русский") "Поделиться логами" else "Share Logs"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error sharing logs: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, if (appLanguage == "Русский") "Лог-файл пуст или еще не создан" else "Log file is empty or not created yet", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Delete Account (Separate Standalone Card)
                    val dangerRed = Color(0xFFFF5252)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, dangerRed.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    ) {
                        SettingsRow(
                            title = Localizations.getString("delete_account", appLanguage),
                            iconRes = com.example.twopchat.R.drawable.ic_delete,
                            iconColor = dangerRed,
                            onSurfaceColor = dangerRed,
                            onSurfaceVariant = onSurfaceVariant,
                            primaryColor = primaryColor,
                            isWarning = true,
                            onClick = { showDeleteAccountDialog = true }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "v 0.0.5",
                        fontSize = 12.sp,
                        color = onSurfaceVariant.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
            "chat_settings" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = if (appLanguage == "Русский") "Настройки чатов" else "Chat Settings",
                        appLanguage = appLanguage,
                        onBackClick = { activeSubPage = null },
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor
                    ) {
                        // Appearance Settings Card
                        Text(
                            text = Localizations.getString("appearance", appLanguage),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {

                                // ── Themes Dropdown Trigger ────────────────────────────
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showThemesPicker = !showThemesPicker }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Тема оформления" else "App Theme",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            text = if (appLanguage == "Русский") "Светлая, цвет акцента, AMOLED" else "Light mode, accent color, AMOLED",
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val themeLabel = when {
                                            !isDarkTheme -> if (appLanguage == "Русский") "Светлая" else "Light"
                                            useAmoled -> "AMOLED"
                                            useCerulean -> "Cerulean"
                                            else -> if (appLanguage == "Русский") "Тёмная" else "Dark"
                                        }
                                        Text(text = themeLabel, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = if (showThemesPicker) "▼" else "❯", fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                }

                                AnimatedVisibility(visible = showThemesPicker) {
                                    Column(
                                        modifier = Modifier.padding(top = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(0.dp)
                                    ) {
                                        // Light Mode Toggle
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(Localizations.getString("light_theme", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                                Text(Localizations.getString("light_theme_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Switch(
                                                checked = !isDarkTheme,
                                                onCheckedChange = { onThemeChanged(!it) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                            )
                                        }

                                        HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                        // Cerulean Blue Toggle
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(Localizations.getString("cerulean_blue", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                                Text(Localizations.getString("cerulean_blue_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Switch(
                                                checked = useCerulean,
                                                onCheckedChange = { onAccentChanged(it) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                            )
                                        }

                                        if (isDarkTheme) {
                                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                            // AMOLED Theme Toggle
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(Localizations.getString("amoled_theme", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                                    Text(Localizations.getString("amoled_theme_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                                }
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Switch(
                                                    checked = useAmoled,
                                                    onCheckedChange = { onAmoledChanged(it) },
                                                    colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // ── Launcher Icon Picker Trigger ───────────────────────
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showLauncherIconsPicker = !showLauncherIconsPicker }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Иконка приложения" else "App Launcher Icon",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            text = if (appLanguage == "Русский") {
                                                "Выберите тему значка для домашнего экрана"
                                            } else {
                                                "Select a style for your home screen app icon"
                                            },
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val activeLabel = when (activeIconAlias) {
                                            "MainActivityAliasDefault" -> "Mint Classic"
                                            "MainActivityAliasBlue" -> "Cerulean Blue"
                                            "MainActivityAliasNoir" -> "Noir Luxury"
                                            "MainActivityAliasNeon" -> "Neon Bright"
                                            else -> "Default"
                                        }
                                        Text(text = activeLabel, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = if (showLauncherIconsPicker) "▼" else "❯", fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                }

                                AnimatedVisibility(visible = showLauncherIconsPicker) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        val iconOptions = listOf(
                                            AppIconOption("MainActivityAliasDefault", "Mint Classic", StealthBlack, MintGreen, "Dark/Mint", com.example.twopchat.R.drawable.ic_logo_default_fg),
                                            AppIconOption("MainActivityAliasBlue", "Cerulean Blue", CeruleanBlue, Color.White, "Cerulean", com.example.twopchat.R.drawable.ic_logo_blue_fg),
                                            AppIconOption("MainActivityAliasNoir", "Noir Luxury", Onyx, ChampagneGold, "Charcoal/Gold", com.example.twopchat.R.drawable.ic_logo_noir_fg),
                                            AppIconOption("MainActivityAliasNeon", "Neon Bright", Color.White, NeonPurple, "Light/Violet", com.example.twopchat.R.drawable.ic_logo_neon_fg)
                                        )

                                        iconOptions.forEach { option ->
                                            val isSelected = activeIconAlias == option.alias
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = if (isSelected) primaryColor.copy(alpha = 0.08f) else surfaceColor),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (activeIconAlias != option.alias) {
                                                            onIconChanged(option.alias)
                                                            Toast.makeText(context, "${option.name} Launcher Icon Selected! Launchers rotate on next restart.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.05f),
                                                        shape = RoundedCornerShape(16.dp)
                                                    )
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier
                                                                .size(46.dp)
                                                                .background(option.bg, shape = RoundedCornerShape(10.dp))
                                                                .border(1.dp, option.fg.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                        ) {
                                                            Image(
                                                                painter = painterResource(id = option.fgRes),
                                                                contentDescription = option.name,
                                                                modifier = Modifier.size(30.dp)
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(14.dp))
                                                        Column {
                                                            Text(text = option.name, fontWeight = FontWeight.SemiBold, color = onSurfaceColor)
                                                            Text(text = option.styleDesc, fontSize = 11.sp, color = onSurfaceVariant)
                                                        }
                                                    }
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = {
                                                            if (activeIconAlias != option.alias) {
                                                                onIconChanged(option.alias)
                                                                Toast.makeText(context, "${option.name} Launcher Icon Selected! Launchers rotate on next restart.", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Отключить анимации" else "Disable animations",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            text = if (appLanguage == "Русский") {
                                                "Мгновенные переходы и меньше нагрузки на процессор и видеочип"
                                            } else {
                                                "Instant transitions with lower CPU and GPU usage"
                                            },
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = reduceMotion,
                                        onCheckedChange = {
                                            reduceMotion = it
                                            sharedPrefs.edit()
                                                .putBoolean(com.example.twopchat.REDUCE_MOTION_SETTING, it)
                                                .apply()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = primaryColor,
                                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Message History / RAM Mode Settings Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Сохранять историю переписок" else "Persist Chat History",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            text = if (appLanguage == "Русский") {
                                                "Если выключено, сообщения будут находиться только в ОЗУ (стираться при выходе из диалога)"
                                            } else {
                                                "If disabled, messages reside strictly in RAM and clear when exiting the chat"
                                            },
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = persistChatHistory,
                                        onCheckedChange = {
                                            persistChatHistory = it
                                            sharedPrefs.edit().putBoolean("persist_chat_history", it).apply()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = primaryColor,
                                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                        )
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Link Previews Toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = Localizations.getString("link_previews", appLanguage),
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            text = Localizations.getString("link_previews_desc", appLanguage),
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = linkPreviewsEnabled,
                                        onCheckedChange = {
                                            linkPreviewsEnabled = it
                                            sharedPrefs.edit().putBoolean("settings_link_previews", it).apply()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = primaryColor,
                                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
            "trackers" -> TrackerSettingsPage(
                appLanguage = appLanguage,
                onBackClick = { activeSubPage = null },
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
            )
            "yggdrasil_peers" -> YggdrasilPeerSettingsPage(
                appLanguage = appLanguage,
                onBackClick = { activeSubPage = null },
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
            )
            "security" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = if (appLanguage == "Русский") "Конфиденциальность" else "Privacy & Security",
                        appLanguage = appLanguage,
                        onBackClick = { activeSubPage = null },
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor
                    ) {
                        // Security & Network Settings Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Stealth Disguise Mode
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("stealth_disguise", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("stealth_disguise_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = stealthDisguise,
                                        onCheckedChange = { checked ->
                                            stealthDisguise = checked
                                            sharedPrefs.edit().putBoolean("settings_stealth_disguise", checked).apply()
                                            if (checked) {
                                                onIconChanged("MainActivityAliasCurrency")
                                                showDisguiseInstructionDialog = true
                                            } else {
                                                onIconChanged(activeIconAlias)
                                                Toast.makeText(context, if (appLanguage == "Русский") "Маскировка выключена." else "Disguise inactive.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Screenshot blocking
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("block_screenshots", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("block_screenshots_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = blockScreenshots,
                                        onCheckedChange = {
                                            blockScreenshots = it
                                            sharedPrefs.edit().putBoolean("settings_screenshots", it).apply()
                                            val activity = context as? android.app.Activity
                                            activity?.let { act ->
                                                if (it) {
                                                    act.window.setFlags(
                                                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                                                    )
                                                } else {
                                                    act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                                }
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Passcode
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("passcode_lock", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("passcode_lock_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = passcodeLock,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                showSetPasscodeDialog = true
                                            } else {
                                                showDisablePasscodeDialog = true
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                if (passcodeLock) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showAutolockDialog = true }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(Localizations.getString("autolock_title", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                            Text(Localizations.getString("autolock_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        val autolockLabel = when (autolockMinutes) {
                                            1 -> Localizations.getString("minutes_1", appLanguage)
                                            5 -> Localizations.getString("minutes_5", appLanguage)
                                            10 -> Localizations.getString("minutes_10", appLanguage)
                                            30 -> Localizations.getString("minutes_30", appLanguage)
                                            else -> "${autolockMinutes}m"
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = autolockLabel, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showSetDuressDialog = true }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(Localizations.getString("duress_pin_title", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                            Text(Localizations.getString("duress_pin_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        val duressPinValue = sharedPrefs.getString("passcode_duress_value", "") ?: ""
                                        val duressSet = duressPinValue.isNotEmpty()
                                        Row(verticalAlignment = Alignment.CenterVertically) {

                                            Text(
                                                text = if (duressSet) Localizations.getString("enabled", appLanguage) else Localizations.getString("disabled", appLanguage),
                                                color = if (duressSet) primaryColor else onSurfaceVariant,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Direct WiFi discovery
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("wifi_discovery", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("wifi_discovery_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = wifiDiscovery,
                                        onCheckedChange = {
                                            wifiDiscovery = it
                                            sharedPrefs.edit().putBoolean("settings_wifi", it).apply()
                                            P2PMessageRelay.setLocalDiscoveryEnabled(context, it)
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                val parsedListenerPort = listenerPortText.toIntOrNull()
                                val listenerPortValid = parsedListenerPort in
                                    P2PPreferences.MIN_LISTENER_PORT..P2PPreferences.MAX_LISTENER_PORT
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (appLanguage == "Русский") "Порт входящих подключений" else "Incoming listener port",
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor,
                                    )
                                    Text(
                                        if (appLanguage == "Русский") {
                                            "Одинаковый порт не требуется: он публикуется через DHT, трекеры и локальную сеть"
                                        } else {
                                            "Peers learn this port through DHT, trackers, and local discovery"
                                        },
                                        fontSize = 12.sp,
                                        color = onSurfaceVariant,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = listenerPortText,
                                            onValueChange = { value ->
                                                listenerPortText = value.filter(Char::isDigit).take(5)
                                            },
                                            singleLine = true,
                                            isError = listenerPortText.isNotEmpty() && !listenerPortValid,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(
                                            enabled = listenerPortValid &&
                                                parsedListenerPort != P2PPreferences.listenerPort(context),
                                            onClick = {
                                                val port = parsedListenerPort ?: return@TextButton
                                                sharedPrefs.edit().putInt(P2PPreferences.LISTENER_PORT, port).apply()
                                                androidx.core.content.ContextCompat.startForegroundService(
                                                    context,
                                                    Intent(context, P2PRelayService::class.java).apply {
                                                        action = P2PRelayService.ACTION_RESTART
                                                    },
                                                )
                                                Toast.makeText(
                                                    context,
                                                    if (appLanguage == "Русский") "P2P-порт изменён на $port" else "P2P port changed to $port",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                        ) {
                                            Text(if (appLanguage == "Русский") "Применить" else "Apply")
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // IPv4 transport
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (appLanguage == "Русский") "Подключение по IPv4" else "IPv4 connections",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            if (appLanguage == "Русский") {
                                                "Анонсировать и использовать прямые IPv4-подключения"
                                            } else {
                                                "Announce and use direct IPv4 connections"
                                            },
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = ipv4Routing,
                                        onCheckedChange = { enabled ->
                                            ipv4Routing = enabled
                                            sharedPrefs.edit().putBoolean("settings_ipv4", enabled).apply()
                                            com.example.twopchat.PythonBridge.setIpv4Enabled(enabled)
                                            com.example.twopchat.P2PMessageRelay.refreshAnnouncement(context)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = primaryColor,
                                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                        )
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Yggdrasil
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("yggdrasil_routing", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("yggdrasil_routing_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = yggdrasilRouting,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                val vpnIntent = VpnService.prepare(context)
                                                if (vpnIntent != null) {
                                                    vpnLauncher.launch(vpnIntent)
                                                } else {
                                                    val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                                                        action = PacketTunnelProvider.ACTION_START
                                                    }
                                                    context.startService(intent)
                                                    yggdrasilRouting = true
                                                    sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
                                                }
                                            } else {
                                                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                                                    action = PacketTunnelProvider.ACTION_STOP
                                                }
                                                context.startService(intent)
                                                yggdrasilRouting = false
                                                sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                TextButton(
                                    onClick = { activeSubPage = "yggdrasil_peers" },
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(
                                        if (appLanguage == "Русский") "Настроить пиры Yggdrasil" else "Configure Yggdrasil peers"
                                    )
                                }

                                TextButton(
                                    onClick = { showRegenerateYggdrasilKeysDialog = true },
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(
                                        if (appLanguage == "Русский") "Сгенерировать новый ключ Yggdrasil" else "Generate new Yggdrasil key"
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
            "notifications" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = if (appLanguage == "Русский") "Уведомления" else "Notifications",
                        appLanguage = appLanguage,
                        onBackClick = { activeSubPage = null },
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor
                    ) {
                        // Notifications Settings Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Master Toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("push_notifications", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("push_notifications_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = notificationsEnabled,
                                        onCheckedChange = {
                                            notificationsEnabled = it
                                            sharedPrefs.edit().putBoolean("settings_notifications", it).apply()
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Previews Toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("message_previews", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("message_previews_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = previewsEnabled,
                                        onCheckedChange = {
                                            previewsEnabled = it
                                            sharedPrefs.edit().putBoolean("settings_previews", it).apply()
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Haptic Feedback Toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("haptic_feedback", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("haptic_feedback_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = hapticFeedbackEnabled,
                                        onCheckedChange = {
                                            hapticFeedbackEnabled = it
                                            sharedPrefs.edit().putBoolean("settings_haptic_feedback", it).apply()
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
            "storage" -> {
                var cacheBytes by remember { mutableLongStateOf(0L) }
                var avatarsBytes by remember { mutableLongStateOf(0L) }
                var logsBytes by remember { mutableLongStateOf(0L) }
                var dbBytes by remember { mutableLongStateOf(0L) }
                var isCalculating by remember { mutableStateOf(true) }
                var showClearConfirmDialog by remember { mutableStateOf(false) }

                fun refreshStorageSizes() {
                    isCalculating = true
                    kotlin.concurrent.thread {
                        try {
                            val cacheDir = context.cacheDir
                            val downloadsDir = java.io.File(context.filesDir, "config/downloads")
                            val cSize = calculateDirSize(cacheDir) + calculateDirSize(downloadsDir)

                            val avatarsDir = java.io.File(context.filesDir, "avatars")
                            val aSize = calculateDirSize(avatarsDir)

                            val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
                            val lSize = if (logFile.exists()) logFile.length() else 0L

                            val dbFile = context.getDatabasePath("2pchat.db")
                            val dbWal = context.getDatabasePath("2pchat.db-wal")
                            val dbShm = context.getDatabasePath("2pchat.db-shm")
                            val dSize = (if (dbFile.exists()) dbFile.length() else 0L) +
                                    (if (dbWal.exists()) dbWal.length() else 0L) +
                                    (if (dbShm.exists()) dbShm.length() else 0L)

                            cacheBytes = cSize
                            avatarsBytes = aSize
                            logsBytes = lSize
                            dbBytes = dSize
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isCalculating = false
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    refreshStorageSizes()
                }

                if (showClearConfirmDialog) {
                    val dangerRed = Color(0xFFE53935)
                    AlertDialog(
                        onDismissRequest = { showClearConfirmDialog = false },
                        title = {
                            Text(
                                text = if (appLanguage == "Русский") "Очистить кэш и память?" else "Clear cache & storage?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )
                        },
                        text = {
                            Text(
                                text = if (appLanguage == "Русский") {
                                    "Будут удалены временные файлы, кэш аватарок, загруженные файлы и логи. История сообщений останется нетронутой."
                                } else {
                                    "Temporary files, cached avatars, downloaded media, and logs will be deleted. Message history will remain intact."
                                },
                                fontSize = 14.sp,
                                color = onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearConfirmDialog = false
                                    kotlin.concurrent.thread {
                                        try {
                                            deleteDirContents(context.cacheDir, keepDir = true)
                                            deleteDirContents(java.io.File(context.filesDir, "config/downloads"), keepDir = true)
                                            deleteDirContents(java.io.File(context.filesDir, "avatars"), keepDir = true)
                                            P2PMessageRelay.peerAvatars.clear()
                                            val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
                                            if (logFile.exists()) {
                                                logFile.writeText("")
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        } finally {
                                            refreshStorageSizes()
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "Память успешно очищена" else "Storage cleared successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = dangerRed,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Очистить" else "Clear",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirmDialog = false }) {
                                Text(
                                    text = if (appLanguage == "Русский") "Отмена" else "Cancel",
                                    color = primaryColor
                                )
                            }
                        },
                        containerColor = surfaceColor,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = if (appLanguage == "Русский") "Данные и память" else "Data & Storage",
                        appLanguage = appLanguage,
                        onBackClick = { activeSubPage = null },
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor
                    ) {
                        val totalBytes = cacheBytes + avatarsBytes + logsBytes + dbBytes

                        // Storage breakdown Card
                        Text(
                            text = if (appLanguage == "Русский") "Использование памяти" else "Storage Usage",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (appLanguage == "Русский") "Всего занято" else "Total Used",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = onSurfaceColor
                                    )
                                    Text(
                                        text = if (isCalculating) "..." else formatStorageSize(totalBytes),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = primaryColor
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // Item: Cache & Media
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(if (appLanguage == "Русский") "Временные файлы и медиа" else "Temporary Cache & Media", fontSize = 14.sp, color = onSurfaceColor)
                                        Text(if (appLanguage == "Русский") "Кэш загрузок и медиафайлов" else "Downloads and media cache", fontSize = 11.sp, color = onSurfaceVariant)
                                    }
                                    Text(if (isCalculating) "..." else formatStorageSize(cacheBytes), fontSize = 14.sp, color = onSurfaceVariant)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Item: Avatars
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(if (appLanguage == "Русский") "Кэш аватарок" else "Cached Avatars", fontSize = 14.sp, color = onSurfaceColor)
                                        Text(if (appLanguage == "Русский") "Аватарки контактов" else "Peer profile pictures", fontSize = 11.sp, color = onSurfaceVariant)
                                    }
                                    Text(if (isCalculating) "..." else formatStorageSize(avatarsBytes), fontSize = 14.sp, color = onSurfaceVariant)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Item: Logs
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(if (appLanguage == "Русский") "Логи приложения" else "App Logs", fontSize = 14.sp, color = onSurfaceColor)
                                        Text(if (appLanguage == "Русский") "Файл системных логов" else "System log file", fontSize = 11.sp, color = onSurfaceVariant)
                                    }
                                    Text(if (isCalculating) "..." else formatStorageSize(logsBytes), fontSize = 14.sp, color = onSurfaceVariant)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Item: Database
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(if (appLanguage == "Русский") "База данных сообщений" else "Message Database", fontSize = 14.sp, color = onSurfaceColor)
                                        Text(if (appLanguage == "Русский") "Зашифрованная история чатов" else "Encrypted chat history", fontSize = 11.sp, color = onSurfaceVariant)
                                    }
                                    Text(if (isCalculating) "..." else formatStorageSize(dbBytes), fontSize = 14.sp, color = onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action: Clear Storage Button Card
                        val dangerRed = Color(0xFFFF5252)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, dangerRed.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Button(
                                    onClick = { showClearConfirmDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = dangerRed.copy(alpha = 0.18f),
                                        contentColor = dangerRed
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isCalculating
                                ) {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_database_storage),
                                        contentDescription = "Clear Storage",
                                        tint = dangerRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (appLanguage == "Русский") "Очистить кэш и память" else "Clear Storage & Cache",
                                        fontWeight = FontWeight.Bold,
                                        color = dangerRed
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
            "help_reference" -> {
                SubPageLayout(
                    title = Localizations.getString("help_reference", appLanguage),
                    appLanguage = appLanguage,
                    onBackClick = { activeSubPage = null },
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = Localizations.getString("help_yggdrasil_title", appLanguage),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = Localizations.getString("help_yggdrasil_desc", appLanguage),
                                    fontSize = 13.sp,
                                    color = onSurfaceColor.copy(alpha = 0.8f),
                                    lineHeight = 20.sp
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = onSurfaceColor.copy(alpha = 0.08f))

                                Text(
                                    text = Localizations.getString("help_e2ee_title", appLanguage),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = Localizations.getString("help_e2ee_desc", appLanguage),
                                    fontSize = 13.sp,
                                    color = onSurfaceColor.copy(alpha = 0.8f),
                                    lineHeight = 20.sp
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = onSurfaceColor.copy(alpha = 0.08f))

                                Text(
                                    text = Localizations.getString("help_privacy_title", appLanguage),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = Localizations.getString("help_privacy_desc", appLanguage),
                                    fontSize = 13.sp,
                                    color = onSurfaceColor.copy(alpha = 0.8f),
                                    lineHeight = 20.sp
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = onSurfaceColor.copy(alpha = 0.08f))

                                Text(
                                    text = Localizations.getString("help_duress_title", appLanguage),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = Localizations.getString("help_duress_desc", appLanguage),
                                    fontSize = 13.sp,
                                    color = onSurfaceColor.copy(alpha = 0.8f),
                                    lineHeight = 20.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(30.dp))
                    }
                }
            }
        }
    }


    if (showAvatarOptions) {
        AlertDialog(
            onDismissRequest = { showAvatarOptions = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarOptions = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel", color = primaryColor)
                }
            },
            title = {
                Text(
                    text = if (appLanguage == "Русский") "Фото профиля" else "Profile Photo",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = {
                            showAvatarOptions = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Выбрать новое фото" else "Choose New Photo",
                            color = primaryColor,
                            fontSize = 15.sp
                        )
                    }
                    TextButton(
                        onClick = {
                            showAvatarOptions = false
                            profilePhotoUri = null
                            profileBitmap = null
                            sharedPrefs.edit().remove("profile_photo_uri").apply()
                            try {
                                val file = java.io.File(context.filesDir, "profile_avatar.jpg")
                                if (file.exists()) {
                                    file.delete()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            com.example.twopchat.P2PMessageRelay.shareAvatarWithConnectedPeers(context)
                            Toast.makeText(context, if (appLanguage == "Русский") "Фото профиля удалено" else "Profile photo removed", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Удалить фото" else "Remove Photo",
                            color = Color.Red,
                            fontSize = 15.sp
                        )
                    }
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Language Selector dialog
    if (showLanguageDialog) {
        val languages = listOf("English", "Русский")
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            title = { Text(Localizations.getString("app_language", appLanguage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLanguageChanged(lang)
                                    showLanguageDialog = false
                                    Toast.makeText(context, "Language changed to $lang", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = lang, fontSize = 15.sp, color = onSurfaceColor)
                            if (lang == appLanguage) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_check_bold),
                                    contentDescription = "Selected",
                                    tint = primaryColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showDisguiseInstructionDialog) {
        AlertDialog(
            onDismissRequest = { showDisguiseInstructionDialog = false },
            title = {
                Text(
                    text = if (appLanguage == "Русский") "Режим маскировки включен" else "Stealth Disguise Activated",
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Text(
                    text = if (appLanguage == "Русский") {
                        "Чтобы войти в 2PChat в будущем:\n\n" +
                        "1. Введите в поле ввода суммы конвертера ровно 777 или 2002.\n\n" +
                        "2. Либо быстро нажмите 3 раза на заголовок «Курсы валют» вверху экрана."
                    } else {
                        "To enter 2PChat in the future:\n\n" +
                        "1. Enter exactly 777 or 2002 in the converter amount field.\n\n" +
                        "2. Or tap the top title \"Currency Rates\" 3 times quickly."

                    },
                    fontSize = 14.sp,
                    color = onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showDisguiseInstructionDialog = false }) {
                    Text(text = if (appLanguage == "Русский") "Понятно" else "Understood", color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Passcode Setup Dialog Flow
    if (showSetPasscodeDialog) {
        var pin1 by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        var isConfirming by remember { mutableStateOf(false) }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSetPasscodeDialog = false },
            title = {
                Text(
                    text = Localizations.getString("set_passcode_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isConfirming) {
                            Localizations.getString("confirm_passcode", appLanguage)
                        } else {
                            Localizations.getString("enter_passcode", appLanguage)
                        },
                        fontSize = 14.sp,
                        color = onSurfaceVariant
                    )
                    
                    TextField(
                        value = if (isConfirming) pin2 else pin1,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                if (isConfirming) pin2 = input else pin1 = input
                                pinError = false
                            }
                        },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = surfaceVariant,
                            unfocusedContainerColor = surfaceVariant,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pinError) {
                        Text(
                            text = Localizations.getString("passcodes_dont_match", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isConfirming) {
                            if (pin1.length == 4) {
                                isConfirming = true
                            }
                        } else {
                            if (pin1 == pin2) {
                                sharedPrefs.edit()
                                    .putString("passcode_value", com.example.twopchat.SecurityUtils.protectPasscode(pin1))
                                    .putBoolean("settings_passcode", true)
                                    .apply()
                                passcodeLock = true
                                showSetPasscodeDialog = false
                                Toast.makeText(context, Localizations.getString("passcode_enabled", appLanguage), Toast.LENGTH_SHORT).show()
                            } else {
                                pinError = true
                                pin2 = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    enabled = if (isConfirming) pin2.length == 4 else pin1.length == 4
                ) {
                    Text(Localizations.getString("continue", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetPasscodeDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Passcode Disable Dialog Flow (requires security verification)
    if (showDisablePasscodeDialog) {
        var enteredPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showDisablePasscodeDialog = false },
            title = {
                Text(
                    text = Localizations.getString("disable_passcode_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = Localizations.getString("enter_current_passcode", appLanguage),
                        fontSize = 14.sp,
                        color = onSurfaceVariant
                    )
                    
                    TextField(
                        value = enteredPin,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                enteredPin = input
                                pinError = false
                            }
                        },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = surfaceVariant,
                            unfocusedContainerColor = surfaceVariant,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pinError) {
                        Text(
                            text = Localizations.getString("invalid_passcode", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val correctPin = sharedPrefs.getString("passcode_value", "") ?: ""
                        if (com.example.twopchat.SecurityUtils.verifyAndMigratePasscode(enteredPin, correctPin, sharedPrefs, "passcode_value")) {
                            sharedPrefs.edit()
                                .putBoolean("settings_passcode", false)
                                .remove("passcode_value")
                                .apply()
                            passcodeLock = false
                            showDisablePasscodeDialog = false
                            Toast.makeText(context, Localizations.getString("passcode_disabled", appLanguage), Toast.LENGTH_SHORT).show()
                        } else {
                            pinError = true
                            enteredPin = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    enabled = enteredPin.length == 4
                ) {
                    Text(Localizations.getString("enter", appLanguage))
                }
            },

            dismissButton = {
                TextButton(onClick = { showDisablePasscodeDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Inactivity Auto-lock Selector Dialog
    if (showAutolockDialog) {
        val options = listOf(1, 5, 10, 30)
        AlertDialog(
            onDismissRequest = { showAutolockDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAutolockDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            title = {
                Text(
                    text = Localizations.getString("autolock_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { minutes ->
                        val label = when (minutes) {
                            1 -> Localizations.getString("minutes_1", appLanguage)
                            5 -> Localizations.getString("minutes_5", appLanguage)
                            10 -> Localizations.getString("minutes_10", appLanguage)
                            30 -> Localizations.getString("minutes_30", appLanguage)
                            else -> "$minutes m"
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autolockMinutes = minutes
                                    sharedPrefs.edit().putInt("passcode_autolock_minutes", minutes).apply()
                                    showAutolockDialog = false
                                    Toast.makeText(context, "Auto-lock timeout set to $label", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = label, fontSize = 15.sp, color = onSurfaceColor)
                            if (minutes == autolockMinutes) {
                                Text(text = "✓", color = primaryColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Delete Account Confirmation Dialog
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = {
                Text(
                    text = Localizations.getString("delete_account_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Text(
                    text = Localizations.getString("delete_account_desc", appLanguage),
                    fontSize = 14.sp,
                    color = onSurfaceVariant
                )
            },
            confirmButton = {
                val dangerRed = Color(0xFFE53935)
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = Localizations.getString("delete", appLanguage),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text(Localizations.getString("cancel", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Set Duress PIN Dialog Flow
    if (showSetDuressDialog) {
        var duressPin1 by remember { mutableStateOf("") }
        var duressPin2 by remember { mutableStateOf("") }
        var isDuressConfirming by remember { mutableStateOf(false) }
        var duressPinError by remember { mutableStateOf(false) }
        var duressMatchesMainError by remember { mutableStateOf(false) }

        val mainPinVal = sharedPrefs.getString("passcode_value", "") ?: ""

        AlertDialog(
            onDismissRequest = { showSetDuressDialog = false },
            title = {
                Text(
                    text = Localizations.getString("set_duress_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isDuressConfirming) {
                            Localizations.getString("confirm_duress_pin", appLanguage)
                        } else {
                            Localizations.getString("enter_duress_pin", appLanguage)
                        },
                        fontSize = 14.sp,
                        color = onSurfaceVariant
                    )
                    
                    TextField(
                        value = if (isDuressConfirming) duressPin2 else duressPin1,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                if (isDuressConfirming) duressPin2 = input else duressPin1 = input
                                duressPinError = false
                                duressMatchesMainError = false
                            }
                        },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = surfaceVariant,
                            unfocusedContainerColor = surfaceVariant,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (duressPinError) {
                        Text(
                            text = Localizations.getString("passcodes_dont_match", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                    if (duressMatchesMainError) {
                        Text(
                            text = Localizations.getString("duress_matches_main_error", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isDuressConfirming) {
                            if (duressPin1.length == 4) {
                                if (com.example.twopchat.SecurityUtils.verifyPasscode(duressPin1, mainPinVal)) {
                                    duressMatchesMainError = true
                                    duressPin1 = ""
                                } else {
                                    isDuressConfirming = true
                                }
                            }
                        } else {
                            if (duressPin1 == duressPin2) {
                                sharedPrefs.edit()
                                    .putString("passcode_duress_value", com.example.twopchat.SecurityUtils.protectPasscode(duressPin1))
                                    .apply()
                                showSetDuressDialog = false

                                Toast.makeText(context, Localizations.getString("duress_enabled", appLanguage), Toast.LENGTH_SHORT).show()
                            } else {
                                duressPinError = true
                                duressPin2 = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    enabled = if (isDuressConfirming) duressPin2.length == 4 else duressPin1.length == 4
                ) {
                    Text(Localizations.getString("continue", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    sharedPrefs.edit().remove("passcode_duress_value").apply()
                    showSetDuressDialog = false
                    Toast.makeText(context, Localizations.getString("duress_disabled", appLanguage), Toast.LENGTH_SHORT).show()
                }) {
                    Text(Localizations.getString("disable", appLanguage), color = Color.Red)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }
}


@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    iconRes: Int,
    iconColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
    isWarning: Boolean = false,
    onClick: () -> Unit
) {
    val warningRed = Color(0xFFFF5252)
    val effectiveOnSurfaceColor = if (isWarning) warningRed else onSurfaceColor
    val effectiveIconColor = if (isWarning) warningRed else iconColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(color = if (isWarning) warningRed.copy(alpha = 0.15f) else iconColor.copy(alpha = 0.15f), shape = CircleShape)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = effectiveIconColor,
                modifier = Modifier.size(18.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = effectiveOnSurfaceColor
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        
        if (value != null) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = primaryColor
            )
        }
    }
}

@Composable
fun SubPageLayout(
    title: String,
    appLanguage: String,
    onBackClick: () -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_back_arrow),
                    contentDescription = if (appLanguage == "Русский") "Назад" else "Back",
                    tint = onSurfaceColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        content()
    }
}
