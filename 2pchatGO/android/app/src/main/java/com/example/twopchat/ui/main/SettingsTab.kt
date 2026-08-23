
package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import org.json.JSONArray
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.Chat
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.*
import com.example.twopchat.service.P2PRelayService
import com.example.twopchat.media.*
import com.example.twopchat.security.*
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import com.example.twopchat.ui.chat.AttachmentImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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

private data class StorageSnapshot(
    val cacheBytes: Long,
    val receivedStickerBytes: Long,
    val avatarsBytes: Long,
    val logsBytes: Long,
    val databaseBytes: Long,
    val mediaUsage: Map<AttachmentCategory, AttachmentCategoryUsage>,
)

@Composable
fun SettingsTab(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    accentScheme: String = if (useCerulean) "cerulean" else "mint",
    onAccentSchemeChanged: (String) -> Unit = {},
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
    val sharedPrefs = remember { com.example.twopchat.config.P2PPreferences.prefs(context) }
    
    // Profile photo states
    var profilePhotoUri by remember { mutableStateOf(sharedPrefs.getString("profile_photo_uri", null)) }
    var profileBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var fullProfileBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pendingCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAvatarOptions by remember { mutableStateOf(false) }
    var showFullScreenAvatar by remember { mutableStateOf(false) }

    LaunchedEffect(profilePhotoUri) {
        withContext(Dispatchers.IO) {
            profileBitmap = com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri, maxDimension = 256)
            fullProfileBitmap = com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri, maxDimension = 2048)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            pendingCropUri = it
        }
    }

    // Dynamic settings states
    var localFingerprint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            localFingerprint = P2PBridgeProvider.get(context).getLocalFingerprint()
        }
    }
    val formattedLocalFingerprint = remember(localFingerprint) {
        if (localFingerprint.isNotBlank()) localFingerprint.chunked(4).joinToString(" ") else ""
    }

    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var aboutMeText by remember { mutableStateOf(sharedPrefs.getString("about_me_profile", "") ?: "") }
    var showEditAboutMeDialog by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_notifications", true)) }
    var previewsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_previews", false)) }
    var blockScreenshots by remember { mutableStateOf(sharedPrefs.getBoolean("settings_screenshots", true)) }
    var incognitoKeyboard by remember { mutableStateOf(P2PPreferences.isIncognitoKeyboardEnabled(context)) }
    var passcodeLock by remember { mutableStateOf(sharedPrefs.getBoolean("settings_passcode", false)) }
    var wifiDiscovery by remember { mutableStateOf(sharedPrefs.getBoolean("settings_wifi", true)) }
    var listenerPortText by remember {
        mutableStateOf(P2PPreferences.listenerPort(context).toString())
    }
    var useNativeGoCore by remember { mutableStateOf(P2PPreferences.isUseNativeGoCore(context)) }
    var ipv4Routing by remember { mutableStateOf(sharedPrefs.getBoolean("settings_ipv4", true)) }
    var upnpEnabled by remember { mutableStateOf(P2PPreferences.isUpnpEnabled(context)) }
    var persistChatHistory by remember { mutableStateOf(sharedPrefs.getBoolean("persist_chat_history", true)) }
    var linkPreviewsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_link_previews", false)) }
    var hapticFeedbackEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_haptic_feedback", true)) }
    var reduceMotion by remember { mutableStateOf(sharedPrefs.getBoolean(com.example.twopchat.REDUCE_MOTION_SETTING, false)) }
    var heroWidgetCollapsed by remember { mutableStateOf(sharedPrefs.getBoolean("settings_hero_widget_collapsed", false)) }
    var stealthDisguise by remember { mutableStateOf(sharedPrefs.getBoolean("settings_stealth_disguise", false)) }
    var showDisguiseInstructionDialog by remember { mutableStateOf(false) }
    
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
    var showSeedBackupDialog by remember { mutableStateOf(false) }
    var showPinForBackupDialog by remember { mutableStateOf(false) }
    var isSearchingSettings by remember { mutableStateOf(false) }
    var settingsSearchQuery by remember { mutableStateOf("") }
    val isTorRunning by TorManager.isTorRunning.collectAsState()

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
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        ),
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
                        
                        // Update identity & announce
                        val bridge = P2PBridgeProvider.get(context)
                        val localFingerprint = bridge.getLocalFingerprint()
                        bridge.configureLocalIdentity(username, localFingerprint, aboutMeText)
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



    val cropUri = pendingCropUri
    if (cropUri != null) {
        com.example.twopchat.ui.onboarding.ImageCropper(
            imageUri = cropUri,
            onCropSuccess = { localPath ->
                profilePhotoUri = localPath
                sharedPrefs.edit().putString("profile_photo_uri", localPath).apply()
                com.example.twopchat.relay.P2PMessageRelay.shareAvatarWithConnectedPeers(context)
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
                    // Top Bar Header (Title + Search Action / Expandable Search Field)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isSearchingSettings) {
                            Text(
                                text = Localizations.tr(appLanguage, "Настройки", "Settings", "Einstellungen", "Ajustes", "Paramètres", "Configurações"),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = primaryColor.copy(alpha = 0.12f),
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable { isSearchingSettings = true }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                                        contentDescription = "Search settings",
                                        tint = primaryColor,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = settingsSearchQuery,
                                onValueChange = { settingsSearchQuery = it },
                                placeholder = {
                                    Text(
                                        Localizations.tr(appLanguage, "Поиск по настройкам...", "Search settings...", "Einstellungen suchen...", "Buscar ajustes...", "Rechercher dans les paramètres...", "Buscar configurações..."),
                                        fontSize = 14.sp,
                                        color = onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
                                keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                                    context = context,
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                                ),
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                                        contentDescription = "Search",
                                        tint = primaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            if (settingsSearchQuery.isNotEmpty()) {
                                                settingsSearchQuery = ""
                                            } else {
                                                isSearchingSettings = false
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close search",
                                            tint = onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = surfaceColor,
                                    unfocusedContainerColor = surfaceColor,
                                    focusedIndicatorColor = primaryColor.copy(alpha = 0.5f),
                                    unfocusedIndicatorColor = onSurfaceColor.copy(alpha = 0.12f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    val isRu = appLanguage == "Русский"
                    val trimmedQuery = settingsSearchQuery.trim().lowercase()

                    val hasPasscode = remember(showSetPasscodeDialog, showDisablePasscodeDialog) { sharedPrefs.contains("passcode_value") }
                    val hasDuressPIN = remember(showSetDuressDialog) { sharedPrefs.contains("passcode_duress_value") }
                    val allowScreenshots = remember { sharedPrefs.getBoolean("allow_screenshots", false) }

                    val deepSettingsList = remember(appLanguage, hasPasscode, hasDuressPIN, allowScreenshots) {
                        listOf(
                            DeepSettingItem(
                                category = if (isRu) "Оформление" else "Appearance",
                                categoryColor = Color(0xFFFFA726),
                                title = if (isRu) "Тема приложения" else "App Theme",
                                subtitle = if (isRu) "Светлая тема, цвет акцента, AMOLED" else "Light mode, accent color, AMOLED",
                                valueBadge = if (isRu) "Настройки" else "Light",
                                keywords = listOf("app theme", "theme", "тема", "оформление", "light", "dark", "amoled", "stealth", "акцент", "цвет"),
                                onClick = { activeSubPage = "chat_settings" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Оформление" else "Appearance",
                                categoryColor = Color(0xFFFFA726),
                                title = if (isRu) "Иконка приложения" else "App Launcher Icon",
                                subtitle = if (isRu) "Выберите стиль иконки на рабочем столе" else "Select a style for your home screen app icon",
                                valueBadge = if (isRu) "Выбрать" else "Mint Classic",
                                keywords = listOf("app launcher icon", "app icon", "icon", "иконка", "значок", "mint classic", "launcher", "иконки"),
                                onClick = { activeSubPage = "chat_settings" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Оформление" else "Appearance",
                                categoryColor = Color(0xFFFF7043),
                                title = if (isRu) "Стикерпаки" else "Sticker Packs",
                                subtitle = if (isRu) "Создание, импорт из Telegram и управление наклейками" else "Create, import from Telegram & manage sticker packs",
                                keywords = listOf("sticker", "stickers", "стикерпак", "стикеры", "паки", "наклейки", "telegram"),
                                onClick = { activeSubPage = "sticker_packs" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Безопасность" else "Security",
                                categoryColor = Color(0xFF66BB6A),
                                title = if (isRu) "Личный ключ безопасности" else "Personal Security Key",
                                subtitle = if (isRu) "Отпечаток (Fingerprint) для сверки личности и сессий" else "Cryptographic fingerprint to verify identity and sessions",
                                valueBadge = if (localFingerprint.isNotBlank()) localFingerprint.take(8) + "…" else null,
                                keywords = listOf("fingerprint", "key", "ключ", "отпечаток", "безопасность", "сверка", "mitm", "identity"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Безопасность" else "Security",
                                categoryColor = Color(0xFF66BB6A),
                                title = if (isRu) "Код-пароль приложения" else "App Passcode Lock",
                                subtitle = if (isRu) "4-значный PIN-код для защиты доступа к чатам" else "4-digit PIN for securing app access",
                                valueBadge = if (hasPasscode) (if (isRu) "Включен" else "ON") else (if (isRu) "Выключен" else "OFF"),
                                keywords = listOf("passcode", "pin", "lock", "пароль", "код", "код-пароль", "пин", "защита", "блокировка"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Безопасность" else "Security",
                                categoryColor = Color(0xFF66BB6A),
                                title = if (isRu) "Тревожный PIN-код (Duress)" else "Duress Emergency PIN",
                                subtitle = if (isRu) "Экстренный код для стирания данных при принуждении" else "Emergency PIN for forced data wipe",
                                valueBadge = if (hasDuressPIN) (if (isRu) "Задан" else "Set") else (if (isRu) "Не задан" else "Not set"),
                                keywords = listOf("duress", "emergency", "тревожный", "экстренный", "паника", "сброс"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Безопасность" else "Security",
                                categoryColor = Color(0xFF66BB6A),
                                title = if (isRu) "Разрешить скриншоты" else "Allow Screenshots",
                                subtitle = if (isRu) "Запрет создания снимков экрана и превью" else "Block screen capture and task switcher preview",
                                valueBadge = if (allowScreenshots) "ON" else "OFF",
                                keywords = listOf("screenshot", "screenshots", "скриншот", "скриншоты", "снимок", "экран", "capture"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Безопасность" else "Security",
                                categoryColor = Color(0xFF66BB6A),
                                title = if (isRu) "Клавиатура инкогнито" else "Incognito Keyboard",
                                subtitle = if (isRu) "Запрос на отключение обучения клавиатуры и сохранения текста" else "Request keyboard to disable personalized learning and logging",
                                valueBadge = if (incognitoKeyboard) "ON" else "OFF",
                                keywords = listOf("incognito", "keyboard", "инкогнито", "клавиатура", "gboard", "swiftkey", "ime", "t9"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Сеть" else "Network",
                                categoryColor = Color(0xFF66BB6A),
                                title = if (isRu) "Входящий порт Direct P2P" else "Direct P2P Listening Port",
                                subtitle = if (isRu) "Сетевой порт для принятия входящих P2P соединений" else "Inbound network port for direct P2P connections",
                                valueBadge = "50001",
                                keywords = listOf("port", "p2p port", "direct p2p", "порт", "прямое соединение", "50001"),
                                onClick = { activeSubPage = "advanced_network" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Сеть" else "Network",
                                categoryColor = Color(0xFF29B6F6),
                                title = if (isRu) "Трекеры и P2P обнаружение" else "Trackers & Peer Discovery",
                                subtitle = if (isRu) "DHT, mDNS локальная сеть и список трекеров" else "DHT, local mDNS discovery & tracker servers",
                                keywords = listOf("tracker", "trackers", "dht", "mdns", "announce", "трекер", "трекеры", "обнаружение"),
                                onClick = { activeSubPage = "trackers" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Анонимизация" else "Anonymization",
                                categoryColor = Color(0xFFAB47BC),
                                title = if (isRu) "Встроенный Tor & Мосты obfs4" else "Embedded Tor & obfs4 Bridges",
                                subtitle = if (isRu) "Луковая маршрутизация, мосты obfs4 и ротация цепочки" else "Onion routing, obfs4 bridges & circuit rotation",
                                keywords = listOf("tor", "onion", "obfs4", "bridge", "bridges", "тор", "мосты", "анонимность", "цепочка", "circuit", "new identity", "ротация"),
                                onClick = { activeSubPage = "tor" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Сеть" else "Network",
                                categoryColor = Color(0xFF26A69A),
                                title = if (isRu) "SOCKS5 / Внешний Прокси" else "SOCKS5 / Custom Proxy",
                                subtitle = if (isRu) "Настройка внешнего SOCKS5 прокси или Orbot" else "Route connections via custom SOCKS5 proxy or Orbot",
                                keywords = listOf("socks5", "proxy", "socks", "прокси", "хост", "порт", "1080", "9050", "orbot"),
                                onClick = { activeSubPage = "trackers" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Оформление" else "Appearance",
                                categoryColor = Color(0xFFFFA726),
                                title = if (isRu) "Сворачивать NexusTab по умолчанию" else "Collapse NexusTab by Default",
                                subtitle = if (isRu) "Компактный режим виджета статуса на экране чатов" else "Compact NexusTab status bar on chats tab",
                                keywords = listOf("nexustab", "nexus", "hero", "widget", "виджет", "сворачивать", "компактный", "статус"),
                                onClick = { activeSubPage = "chat_settings" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Сеть" else "Network",
                                categoryColor = Color(0xFFAB47BC),
                                title = if (isRu) "Настройки Yggdrasil" else "Yggdrasil Settings",
                                subtitle = if (isRu) "Маршрутизация mesh-сети, пиры и генерация ключей" else "Mesh routing, public & custom peers, key management",
                                keywords = listOf("yggdrasil", "peers", "mesh", "vpn", "nodes", "пиры", "узлы", "маршрутизация", "ключ", "ipv6", "иггдрасиль"),
                                onClick = { activeSubPage = "yggdrasil_peers" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Уведомления" else "Notifications",
                                categoryColor = Color(0xFFEF5350),
                                title = if (isRu) "Уведомления и звуки" else "Notifications & Sounds",
                                subtitle = if (isRu) "Звуковые сигналы, вибрация и превью сообщений" else "Sounds, vibration & message previews",
                                keywords = listOf("notification", "notifications", "sound", "sounds", "vibration", "preview", "уведомление", "уведомления", "звуки", "превью"),
                                onClick = { activeSubPage = "notifications" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Память" else "Storage",
                                categoryColor = Color(0xFF26A69A),
                                title = if (isRu) "Данные и память (Кэш)" else "Data & Storage (Cache)",
                                subtitle = if (isRu) "Использование памяти и очистка кэша файлов" else "Storage usage & clearing media cache",
                                keywords = listOf("data", "storage", "cache", "memory", "кэш", "память", "данные", "очистить", "медиа"),
                                onClick = { activeSubPage = "storage" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Статистика" else "Stats",
                                categoryColor = Color(0xFF42A5F5),
                                title = if (isRu) "Использование сети (Трафик)" else "Network Data Usage",
                                subtitle = if (isRu) "Статистика входящего и исходящего P2P трафика" else "Inbound and outbound P2P traffic statistics",
                                keywords = listOf("network usage", "traffic", "bytes", "трафик", "сеть", "байты", "статистика"),
                                onClick = { activeSubPage = "network_usage" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Система" else "System",
                                categoryColor = Color(0xFFEC407A),
                                title = if (isRu) "Язык приложения" else "App Language",
                                subtitle = if (isRu) "Переключение между Русским и English" else "Switch between Russian and English",
                                valueBadge = appLanguage,
                                keywords = listOf("language", "ru", "en", "язык", "русский", "английский", "english"),
                                onClick = { activeSubPage = "language" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Справка" else "Help",
                                categoryColor = Color(0xFF7E57C2),
                                title = if (isRu) "Справка и руководства" else "Help & Reference",
                                subtitle = if (isRu) "Ответы на частые вопросы и руководства P2P" else "FAQ & P2P protocol reference guides",
                                keywords = listOf("help", "reference", "faq", "справка", "помощь", "вопросы", "руководство"),
                                onClick = { activeSubPage = "help_reference" }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Отладка" else "Debug",
                                categoryColor = Color(0xFF78909C),
                                title = if (isRu) "Сетевой отладчик и Логи" else "Network Diagnostics & Logs",
                                subtitle = if (isRu) "Мониторинг событий сети и соединений live" else "Live monitoring of P2P network events",
                                keywords = listOf("log", "logs", "debugger", "diagnostics", "логи", "отладчик", "отладка"),
                                onClick = { onShowLogs() }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Отладка" else "Debug",
                                categoryColor = Color(0xFF8D6E63),
                                title = if (isRu) "Экспорт логов приложения" else "Export App Logs",
                                subtitle = if (isRu) "Поделиться файлом логов app.log" else "Share app.log file",
                                keywords = listOf("export", "share", "file", "экспорт", "поделиться", "лог-файл", "app.log"),
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
                                            context.startActivity(android.content.Intent.createChooser(shareIntent, if (isRu) "Поделиться логами" else "Share Logs"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error sharing logs: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, if (isRu) "Лог-файл пуст или еще не создан" else "Log file is empty or not created yet", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ),
                            DeepSettingItem(
                                category = if (isRu) "Опасная зона" else "Danger Zone",
                                categoryColor = Color(0xFFFF5252),
                                title = if (isRu) "Удалить аккаунт и данные" else "Delete Account & Data",
                                subtitle = if (isRu) "Полное удаление ключей и истории сообщений" else "Permanently wipe identity keys and history",
                                keywords = listOf("delete", "wipe", "remove", "account", "удалить", "стереть", "аккаунт"),
                                onClick = { showDeleteAccountDialog = true }
                            )
                        )
                    }

                    val matchingDeepResults = remember(trimmedQuery, deepSettingsList) {
                        if (trimmedQuery.isEmpty()) emptyList()
                        else deepSettingsList.filter { item ->
                            item.title.lowercase().contains(trimmedQuery) ||
                                    item.subtitle.lowercase().contains(trimmedQuery) ||
                                    item.category.lowercase().contains(trimmedQuery) ||
                                    item.keywords.any { it.lowercase().contains(trimmedQuery) }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSearchingSettings && trimmedQuery.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (matchingDeepResults.isNotEmpty()) {
                                SettingsSectionHeader(
                                    if (isRu) "НАЙДЕННЫЕ НАСТРОЙКИ (${matchingDeepResults.size})" else "SEARCH RESULTS (${matchingDeepResults.size})",
                                    primaryColor
                                )
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                ) {
                                    Column {
                                        matchingDeepResults.forEachIndexed { index, item ->
                                            if (index > 0) {
                                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))
                                            }
                                            DeepSearchResultRow(
                                                category = item.category,
                                                categoryColor = item.categoryColor,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                                valueBadge = item.valueBadge,
                                                onSurfaceColor = onSurfaceColor,
                                                onSurfaceVariant = onSurfaceVariant,
                                                primaryColor = primaryColor,
                                                onClick = item.onClick
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Empty Search State
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                                            contentDescription = null,
                                            tint = onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = if (isRu) "Ничего не найдено" else "No settings found",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = onSurfaceColor
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (isRu) "Попробуйте изменить поисковый запрос" else "Try changing your search query",
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isSearchingSettings || trimmedQuery.isEmpty()) {
                        // Hero Profile Card with interactive photo selector & identity copy action
                        val discoveryCode = remember { P2PPreferences.getRendezvousCode(context) }
                        val contactAddress = remember(username, discoveryCode) { "$username#$discoveryCode" }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 12.dp)
                                .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Profile Photo container (clickable)
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
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
                                                .border(1.5.dp, primaryColor, CircleShape)
                                        ) {
                                            val avatarBmp = profileBitmap
                                            if (avatarBmp != null) {
                                                Image(
                                                    bitmap = avatarBmp.asImageBitmap(),
                                                    contentDescription = "Profile Photo",
                                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                                )
                                            } else {
                                                Icon(
                                                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_add_photo_smiley),
                                                    contentDescription = "No Profile Photo",
                                                    tint = primaryColor,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                        // Camera badge — bottom-right corner
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .align(Alignment.BottomEnd)
                                                .background(primaryColor, shape = CircleShape)
                                                .border(1.5.dp, surfaceColor, CircleShape)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_camera),
                                                contentDescription = "Change photo",
                                                tint = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = username,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 19.sp,
                                                color = onSurfaceColor
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // P2P Verified pill badge
                                            Surface(
                                                color = primaryColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "P2P",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = primaryColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = contactAddress,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }

                                    // Quick Address Copy Button
                                    Surface(
                                        color = primaryColor.copy(alpha = 0.12f),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clickable {
                                                com.example.twopchat.copyTextToClipboard(context, "Contact Address", contactAddress)
                                                Toast.makeText(
                                                    context,
                                                    if (appLanguage == "Русский") "Адрес контакта скопирован" else "Contact address copied",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Icon(
                                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_copy_key),
                                                contentDescription = "Copy address",
                                                tint = primaryColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Interactive Bio Card Pill
                                Surface(
                                    color = onSurfaceColor.copy(alpha = 0.04f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showEditAboutMeDialog = true }
                                        .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_edit),
                                            contentDescription = "Edit bio",
                                            tint = primaryColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (aboutMeText.isEmpty()) {
                                                if (appLanguage == "Русский") "О себе: Нажмите, чтобы добавить..." else "About me: Tap to add..."
                                            } else {
                                                if (appLanguage == "Русский") "О себе: $aboutMeText" else "About me: $aboutMeText"
                                            },
                                            fontSize = 12.sp,
                                            color = if (aboutMeText.isEmpty()) onSurfaceVariant.copy(alpha = 0.7f) else onSurfaceColor.copy(alpha = 0.9f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        // Group 1: 🛡 АНОНИМНОСТЬ И TOR (Anonymity & Tor)
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🛡 АНОНИМНОСТЬ И TOR", "🛡 ANONYMITY & TOR", "🛡 ANONYMITÄT & TOR", "🛡 ANONIMATO Y TOR", "🛡 ANONYMAT & TOR", "🛡 ANONIMATO & TOR"),
                            Color(0xFF8B5CF6)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                        ) {
                            Column {
                                SettingsRow(
                                    title = if (appLanguage == "Русский") "Tor и скрытые сервисы (.onion)" else "Tor & Hidden Services (.onion)",
                                    subtitle = if (appLanguage == "Русский") "Встроенный демон, мосты obfs4, смена личности" else "Embedded daemon, obfs4 bridges, circuit renewal",
                                    value = if (isTorRunning) "● Active" else "○ Standby",
                                    iconRes = com.example.twopchat.R.drawable.ic_tor,
                                    iconColor = Color(0xFF8B5CF6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    useOriginalIconColors = true,
                                    onClick = { activeSubPage = "tor" }
                                )
                            }
                        }

                        // Group 2: 🔐 БЕЗОПАСНОСТЬ И ДОСТУП (Security & Access)
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🔐 БЕЗОПАСНОСТЬ И ДОСТУП", "🔐 SECURITY & ACCESS", "🔐 SICHERHEIT & ZUGRIFF", "🔐 SEGURIDAD Y ACCESO", "🔐 SÉCURITÉ ET ACCÈS", "🔐 SEGURANÇA E ACESSO"),
                            Color(0xFF10B981)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                        ) {
                            Column {
                                SettingsRow(
                                    title = if (appLanguage == "Русский") "Ключ безопасности (Fingerprint)" else "Security Key (Fingerprint)",
                                    subtitle = if (appLanguage == "Русский") "Сверка отпечатка для подтверждения личности" else "Verify key fingerprint with contacts",
                                    value = if (localFingerprint.isNotBlank()) localFingerprint.take(8) + "…" else null,
                                    iconRes = com.example.twopchat.R.drawable.ic_shield_status,
                                    iconColor = Color(0xFF3B82F6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "security" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = if (appLanguage == "Русский") "Код-пароль и защита входа" else "Passcode Lock & Access",
                                    subtitle = if (appLanguage == "Русский") "4-значный PIN, экстренный Duress PIN, автоблокировка" else "4-digit PIN, emergency Duress PIN, auto-lock",
                                    value = if (hasPasscode) (if (appLanguage == "Русский") "Включен" else "ON") else (if (appLanguage == "Русский") "Выкл" else "OFF"),
                                    iconRes = com.example.twopchat.R.drawable.ic_shield_status,
                                    iconColor = Color(0xFF10B981),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "security" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = if (appLanguage == "Русский") "Приватность и Стелс-маскировка" else "Privacy & Stealth Disguise",
                                    subtitle = if (appLanguage == "Русский") "Запрет скриншотов, защита от перехвата, калькулятор" else "Screenshot blocking, app switcher guard, disguise",
                                    value = if (stealthDisguise) (if (appLanguage == "Русский") "Стелс" else "Stealth") else null,
                                    iconRes = com.example.twopchat.R.drawable.ic_eye,
                                    iconColor = Color(0xFF059669),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "security" }
                                )
                            }
                        }

                        // Group 3: 🌐 СЕТЬ И ПРОДВИНУТЫЕ ПАРАМЕТРЫ (Network & Advanced)
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🌐 СЕТЬ И ПРОДВИНУТЫЕ ПАРАМЕТРЫ", "🌐 NETWORK & ADVANCED", "🌐 NETZWERK & ERWEITERT", "🌐 RED Y AVANZADO", "🌐 RÉSEAU ET AVANCÉ", "🌐 REDE E AVANÇADO"),
                            Color(0xFF0284C7)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                        ) {
                            Column {
                                SettingsRow(
                                    title = Localizations.getString("yggdrasil_peers_title", appLanguage),
                                    subtitle = Localizations.getString("yggdrasil_peers_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_quick_link,
                                    iconColor = Color(0xFF0284C7),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "yggdrasil_peers" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("trackers_discovery_title", appLanguage),
                                    subtitle = Localizations.getString("trackers_discovery_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_menu_search,
                                    iconColor = Color(0xFF06B6D4),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "trackers" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.tr(appLanguage, "Продвинутые параметры сети", "Advanced Network Settings", "Erweiterte Netzwerkeinstellungen", "Ajustes de red avanzados", "Paramètres réseau avancés", "Configurações avançadas de rede"),
                                    subtitle = Localizations.tr(appLanguage, "Порт, UPnP (Авто-NAT), IPv4, WiFi Direct", "Listener port, UPnP, IPv4, WiFi Direct", "Port, UPnP, IPv4, WiFi Direct", "Puerto, UPnP, IPv4, WiFi Direct", "Port, UPnP, IPv4, WiFi Direct", "Porta, UPnP, IPv4, WiFi Direct"),
                                    iconRes = com.example.twopchat.R.drawable.ic_menu_settings,
                                    iconColor = Color(0xFF0EA5E9),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "advanced_network" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("network_usage_title", appLanguage),
                                    subtitle = Localizations.getString("network_usage_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_quick_ip,
                                    iconColor = Color(0xFF3B82F6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "network_usage" }
                                )
                            }
                        }

                        // Group 4: 🎨 ОФОРМЛЕНИЕ И ЯЗЫК (Appearance & Language)
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🎨 ОФОРМЛЕНИЕ И ЯЗЫК", "🎨 APPEARANCE & LANGUAGE", "🎨 ERSCHEINUNGSBILD & SPRACHE", "🎨 APARIENCIA E IDIOMA", "🎨 APPARENCE ET LANGUE", "🎨 APARÊNCIA E IDIOMA"),
                            Color(0xFFF59E0B)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                        ) {
                            Column {
                                SettingsRow(
                                    title = Localizations.getString("chat_settings_theme_title", appLanguage),
                                    subtitle = Localizations.getString("chat_settings_theme_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_menu_chats,
                                    iconColor = Color(0xFFF59E0B),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "chat_settings" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("sticker_packs_title", appLanguage),
                                    subtitle = Localizations.getString("sticker_packs_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_sticker_smile,
                                    iconColor = Color(0xFFFB923C),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "sticker_packs" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("notifications_sounds_title", appLanguage),
                                    subtitle = Localizations.getString("notifications_sounds_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_notifications,
                                    iconColor = Color(0xFFEF4444),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "notifications" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("data_storage_title", appLanguage),
                                    subtitle = Localizations.getString("data_storage_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_database_storage,
                                    iconColor = Color(0xFF14B8A6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "storage" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("language", appLanguage),
                                    subtitle = Localizations.getString("choose_app_language", appLanguage),
                                    value = appLanguage,
                                    iconRes = com.example.twopchat.R.drawable.ic_language_translate,
                                    iconColor = Color(0xFFEC4899),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "language" }
                                )
                            }
                        }

                        // Group 5: 📖 СПРАВОЧНИК И ОБУЧЕНИЕ (Help & Guides)
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "📖 СПРАВОЧНИК И ОБУЧЕНИЕ", "📖 HELP & GUIDES", "📖 HILFE & ANLEITUNGEN", "📖 AYUDA Y GUÍAS", "📖 AIDE ET GUIDES", "📖 AJUDA E GUIAS"),
                            Color(0xFF6366F1)
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.75.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                        ) {
                            Column {
                                SettingsRow(
                                    title = Localizations.getString("help_reference", appLanguage),
                                    subtitle = Localizations.getString("help_reference_desc", appLanguage),
                                    iconRes = com.example.twopchat.R.drawable.ic_help_question,
                                    iconColor = Color(0xFF6366F1),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "help_reference" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("net_diag_logs", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Мониторинг событий сети и соединений live" else "Live monitoring of P2P network events",
                                    iconRes = com.example.twopchat.R.drawable.ic_menu_settings,
                                    iconColor = Color(0xFF64748B),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { onShowLogs() }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("export_app_logs", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Поделиться файлом логов app.log" else "Share app.log file",
                                    iconRes = com.example.twopchat.R.drawable.ic_quick_ip,
                                    iconColor = Color(0xFF78716C),
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

                        Spacer(modifier = Modifier.height(12.dp))

                        // Delete Account (Danger Zone)
                        val dangerRed = Color(0xFFFF5252)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.75.dp, dangerRed.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                        ) {
                            SettingsRow(
                                title = Localizations.getString("delete_account", appLanguage),
                                subtitle = if (appLanguage == "Русский") "Полное удаление ключей и истории сообщений" else "Permanently wipe identity keys and history",
                                iconRes = com.example.twopchat.R.drawable.ic_delete,
                                iconColor = dangerRed,
                                onSurfaceColor = dangerRed,
                                onSurfaceVariant = onSurfaceVariant,
                                primaryColor = primaryColor,
                                isWarning = true,
                                onClick = { showDeleteAccountDialog = true }
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "2PChat v0.0.7 • Go Core v1.2 (P2P)",
                            fontSize = 12.sp,
                            color = onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                    }
                }
            }
            "language" -> LanguageSettingsPage(
                appLanguage = appLanguage,
                onLanguageChanged = onLanguageChanged,
                onBackClick = { activeSubPage = null },
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                primaryColor = primaryColor
            )
            "sticker_packs" -> StickerPackManagerPage(
                appLanguage = appLanguage,
                onBackClick = { activeSubPage = null },
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
            )
            "network_usage" -> NetworkUsagePage(
                appLanguage = appLanguage,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                primaryColor = primaryColor,
                onBackClick = { activeSubPage = null },
            )
            "chat_settings" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = Localizations.tr(appLanguage, "Тема и Оформление", "Appearance & Theme", "Erscheinungsbild & Theme", "Apariencia y Tema", "Apparence et Thème", "Aparência e Tema"),
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
                                            accentScheme == "purple" -> "Amethyst"
                                            accentScheme == "amber" -> "Amber"
                                            useCerulean || accentScheme == "cerulean" -> "Cerulean"
                                            else -> if (appLanguage == "Русский") "Тёмная" else "Dark"
                                        }
                                        Text(text = themeLabel, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = if (showThemesPicker) "▼" else "❯", fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                }

                                AnimatedVisibility(visible = showThemesPicker) {
                                    VisualThemeSelector(
                                        isDarkTheme = isDarkTheme,
                                        onThemeChanged = onThemeChanged,
                                        useCerulean = useCerulean,
                                        onAccentChanged = onAccentChanged,
                                        accentScheme = accentScheme,
                                        onAccentSchemeChanged = onAccentSchemeChanged,
                                        useAmoled = useAmoled,
                                        onAmoledChanged = onAmoledChanged,
                                        appLanguage = appLanguage,
                                        primaryColor = primaryColor,
                                        surfaceColor = surfaceColor,
                                        onSurfaceColor = onSurfaceColor,
                                        onSurfaceVariant = onSurfaceVariant,
                                    )
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

                        Spacer(modifier = Modifier.height(10.dp))

                        // Hero Widget Collapsed Setting
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .border(1.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (appLanguage == "Русский") "Сворачивать NexusTab по умолчанию" else "Collapse NexusTab by Default",
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor
                                    )
                                    Text(
                                        text = if (appLanguage == "Русский") "Компактный режим виджета статуса на экране чатов" else "Compact NexusTab status bar on chats tab",
                                        fontSize = 12.sp,
                                        color = onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Switch(
                                    checked = heroWidgetCollapsed,
                                    onCheckedChange = {
                                        heroWidgetCollapsed = it
                                        sharedPrefs.edit().putBoolean("settings_hero_widget_collapsed", it).apply()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = primaryColor,
                                        checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                    )
                                )
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
            "tor" -> TorSettingsPage(
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
                        title = Localizations.tr(appLanguage, "Безопасность и Доступ", "Security & Access", "Sicherheit & Zugriff", "Seguridad y Acceso", "Sécurité et Accès", "Segurança e Acesso"),
                        appLanguage = appLanguage,
                        onBackClick = { activeSubPage = null },
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor
                    ) {
                        // Security Key / Fingerprint Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        painter = painterResource(com.example.twopchat.R.drawable.ic_shield_status),
                                        contentDescription = "Security Fingerprint",
                                        tint = primaryColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = Localizations.tr(appLanguage, "Личный ключ безопасности", "Personal Security Key", "Persönlicher Sicherheitsschlüssel", "Huella de seguridad personal", "Empreinte de sécurité personnelle", "Impressão digital de segurança pessoal"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = onSurfaceColor
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = Localizations.tr(appLanguage, "Сверьте эту строку с собеседником по доверенному каналу для защиты от подмены ключей (MITM) и подтверждения личности.", "Compare this string with your contact over a trusted channel to verify identity and protect against MITM.", "Gleichen Sie diese Zeichenkette mit Ihrem Kontakt über einen vertrauenswürdigen Kanal ab.", "Compare esta cadena con su contacto a través de un canal de confianza para verificar identidad.", "Comparez cette chaîne avec votre contact via un canal de confiance pour vérifier l'identité.", "Compare esta string com seu contato por um canal confiável."),
                                    fontSize = 12.sp,
                                    color = onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = surfaceVariant.copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.25f))
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = if (formattedLocalFingerprint.isNotBlank()) formattedLocalFingerprint else if (appLanguage == "Русский") "Инициализация ключа..." else "Initializing key...",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = onSurfaceColor,
                                            modifier = Modifier.padding(12.dp),
                                            lineHeight = 20.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        if (localFingerprint.isNotBlank()) {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("2PChat Security Key", localFingerprint)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(
                                                context,
                                                Localizations.tr(appLanguage, "Личный ключ скопирован", "Security key copied", "Sicherheitsschlüssel kopiert", "Clave de seguridad copiada", "Clé de sécurité copiée", "Chave de segurança copiada"),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = Localizations.tr(appLanguage, "Скопировать ключ", "Copy Security Key", "Schlüssel kopieren", "Copiar clave", "Copier la clé", "Copiar chave"),
                                        color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Security & Access Settings Card
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

                                // Incognito Keyboard
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(Localizations.getString("incognito_keyboard", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                                        Text(Localizations.getString("incognito_keyboard_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = incognitoKeyboard,
                                        onCheckedChange = {
                                            incognitoKeyboard = it
                                            P2PPreferences.setIncognitoKeyboardEnabled(context, it)
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

                                val isRootedDevice = remember { com.example.twopchat.security.RootDetectionHelper.isRooted() }
                                if (isRootedDevice) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFEF5350).copy(alpha = 0.12f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("⚠️", fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = if (appLanguage == "Русский") "Обнаружены ROOT-права" else "ROOT Privileges Detected",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = Color(0xFFEF5350)
                                                )
                                                Text(
                                                    text = if (appLanguage == "Русский") "Безопасность оперативной памяти (RAM) снижена. Рекомендуем включить Блокировку кодом." else "In-memory security is reduced. Enabling Passcode Lock is strongly recommended.",
                                                    fontSize = 11.sp,
                                                    color = onSurfaceColor.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (sharedPrefs.contains("passcode_value")) {
                                                showPinForBackupDialog = true
                                            } else {
                                                showSeedBackupDialog = true
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = Localizations.getString("seed_backup_title", appLanguage),
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            text = Localizations.getString("seed_backup_desc", appLanguage),
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
            "advanced_network" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = Localizations.tr(appLanguage, "Параметры сети", "Network Parameters", "Netzwerkparameter", "Parámetros de red", "Paramètres réseau", "Parâmetros de rede"),
                        appLanguage = appLanguage,
                        onBackClick = { activeSubPage = null },
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
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
                                            keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                                                context = context,
                                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                                            ),
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

                                // Experimental Go Core toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (appLanguage == "Русский") "Движок P2P" else "P2P Engine",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            if (appLanguage == "Русский") {
                                                "Нативное Go-ядро (lib2pcore.so) — максимальная производительность, 0% GIL."
                                            } else {
                                                "Native Go core (lib2pcore.so) — peak performance, 0% GIL."
                                            },
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Surface(
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Go Active" else "Go Active",
                                            color = Color(0xFF4CAF50),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
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
                                            com.example.twopchat.relay.P2PMessageRelay.refreshAnnouncement(context)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = primaryColor,
                                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                                        )
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                                // UPnP Port Mapping
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (appLanguage == "Русский") "Проброс портов UPnP (Авто-NAT)" else "UPnP Port Mapping",
                                            fontWeight = FontWeight.Medium,
                                            color = onSurfaceColor
                                        )
                                        Text(
                                            if (appLanguage == "Русский") {
                                                "Автоматический проброс порта на роутере. По умолчанию выключен для повышенной безопасности."
                                            } else {
                                                "Automatically map port on router. Disabled by default for enhanced security."
                                            },
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Switch(
                                        checked = upnpEnabled,
                                        onCheckedChange = { enabled ->
                                            upnpEnabled = enabled
                                            sharedPrefs.edit().putBoolean(P2PPreferences.UPNP_ENABLED, enabled).apply()
                                            if (enabled) {
                                                Toast.makeText(
                                                    context,
                                                    if (appLanguage == "Русский") "UPnP включён. Выполняется проброс порта..." else "UPnP enabled. Attempting port mapping...",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    if (appLanguage == "Русский") "UPnP выключён." else "UPnP disabled.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            com.example.twopchat.relay.P2PMessageRelay.refreshAnnouncement(context)
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
            "notifications" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SubPageLayout(
                        title = Localizations.tr(appLanguage, "Уведомления", "Notifications", "Benachrichtigungen", "Notificaciones", "Notifications", "Notificações"),
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
                val storageScope = rememberCoroutineScope()
                var cacheBytes by remember { mutableLongStateOf(0L) }
                var receivedStickerBytes by remember { mutableLongStateOf(0L) }
                var avatarsBytes by remember { mutableLongStateOf(0L) }
                var logsBytes by remember { mutableLongStateOf(0L) }
                var dbBytes by remember { mutableLongStateOf(0L) }
                var mediaUsage by remember {
                    mutableStateOf(
                        AttachmentCategory.entries.associateWith {
                            AttachmentCategoryUsage()
                        },
                    )
                }
                var isCalculating by remember { mutableStateOf(true) }
                var isClearingMedia by remember { mutableStateOf(false) }
                var stickerCacheLimitMb by remember {
                    mutableIntStateOf(P2PPreferences.stickerCacheLimitMb(context))
                }
                var showClearConfirmDialog by remember { mutableStateOf(false) }
                var showMediaCleanupDialog by remember { mutableStateOf(false) }
                var selectedMediaCategories by remember {
                    mutableStateOf(emptySet<AttachmentCategory>())
                }

                fun refreshStorageSizes() {
                    isCalculating = true
                    storageScope.launch {
                        val sizes = runCatching {
                            withContext(Dispatchers.IO) {
                                val cacheDir = context.cacheDir
                                val downloadsDir = java.io.File(context.filesDir, "config/downloads")
                                val attachmentsDir = java.io.File(context.filesDir, "attachments")
                                val cSize = calculateDirSize(cacheDir) +
                                    calculateDirSize(downloadsDir) +
                                    calculateDirSize(attachmentsDir)
                                val receivedStickersSize = calculateDirSize(
                                    StickerSupport.receivedCacheDirectory(context),
                                )

                                val avatarsDir = java.io.File(context.filesDir, "avatars")
                                val aSize = calculateDirSize(avatarsDir)

                                val logFile = java.io.File(
                                    java.io.File(context.filesDir, "config"),
                                    "app.log",
                                )
                                val lSize = if (logFile.exists()) logFile.length() else 0L

                                val dbDir = context.getDatabasePath("twopchat.db").parentFile
                                val dSize = calculateDirSize(dbDir)
                                val usage = AttachmentStorageManager.calculateUsage(context)
                                StorageSnapshot(
                                    cSize,
                                    receivedStickersSize,
                                    aSize,
                                    lSize,
                                    dSize,
                                    usage,
                                )
                            }
                        }.getOrNull()
                        if (sizes != null) {
                            cacheBytes = sizes.cacheBytes
                            receivedStickerBytes = sizes.receivedStickerBytes
                            avatarsBytes = sizes.avatarsBytes
                            logsBytes = sizes.logsBytes
                            dbBytes = sizes.databaseBytes
                            mediaUsage = sizes.mediaUsage
                        }
                        isCalculating = false
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
                                    "Будут удалены временные файлы, кэш аватарок, полученные стикеры, загруженные файлы и логи. История сообщений останется нетронутой."
                                } else {
                                    "Temporary files, cached avatars, received stickers, downloaded media, and logs will be deleted. Message history will remain intact."
                                },
                                fontSize = 14.sp,
                                color = onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearConfirmDialog = false
                                    isClearingMedia = true
                                    storageScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                AttachmentStorageManager.clear(
                                                    context,
                                                    AttachmentCategory.entries.toSet(),
                                                )
                                                deleteDirContents(context.cacheDir, keepDir = true)
                                                deleteDirContents(
                                                    java.io.File(context.filesDir, "config/downloads"),
                                                    keepDir = true,
                                                )
                                                deleteDirContents(
                                                    java.io.File(context.filesDir, "avatars"),
                                                    keepDir = true,
                                                )
                                                val logFile = java.io.File(
                                                    java.io.File(context.filesDir, "config"),
                                                    "app.log",
                                                )
                                                if (logFile.exists()) {
                                                    logFile.writeText("")
                                                }
                                            }
                                            P2PMessageRelay.peerAvatars.clear()
                                            AttachmentImageCache.clear()
                                            Toast.makeText(
                                                context,
                                                if (appLanguage == "Русский") {
                                                    "Память успешно очищена"
                                                } else {
                                                    "Storage cleared successfully"
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(
                                                context,
                                                if (appLanguage == "Русский") {
                                                    "Не удалось очистить память"
                                                } else {
                                                    "Could not clear storage"
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } finally {
                                            isClearingMedia = false
                                            refreshStorageSizes()
                                        }
                                    }
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

                if (showMediaCleanupDialog) {
                    val categoryLabels = mapOf(
                        AttachmentCategory.VIDEO to (
                            if (appLanguage == "Русский") "Видео" else "Videos"
                        ),
                        AttachmentCategory.IMAGE to (
                            if (appLanguage == "Русский") "Изображения" else "Images"
                        ),
                        AttachmentCategory.FILE to (
                            if (appLanguage == "Русский") "Документы и файлы" else "Documents & files"
                        ),
                        AttachmentCategory.VOICE to (
                            if (appLanguage == "Русский") "Голосовые сообщения" else "Voice messages"
                        ),
                        AttachmentCategory.STICKER to (
                            if (appLanguage == "Русский") "Стикеры собеседников" else "Peer stickers"
                        ),
                    )
                    AlertDialog(
                        onDismissRequest = {
                            if (!isClearingMedia) showMediaCleanupDialog = false
                        },
                        title = {
                            Text(
                                text = if (appLanguage == "Русский") {
                                    "Удалить медиа по типу"
                                } else {
                                    "Delete media by type"
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor,
                            )
                        },
                        text = {
                            Column {
                                Text(
                                    text = if (appLanguage == "Русский") {
                                        "Файлы будут удалены с устройства, но сообщения, подписи и даты останутся в чатах."
                                    } else {
                                        "Files will be removed from this device, while messages, captions, and dates remain in chats."
                                    },
                                    color = onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                AttachmentCategory.entries.forEach { category ->
                                    val usage = mediaUsage[category] ?: AttachmentCategoryUsage()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isClearingMedia) {
                                                selectedMediaCategories =
                                                    if (category in selectedMediaCategories) {
                                                        selectedMediaCategories - category
                                                    } else {
                                                        selectedMediaCategories + category
                                                    }
                                            }
                                            .padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = category in selectedMediaCategories,
                                            onCheckedChange = { checked ->
                                                selectedMediaCategories = if (checked) {
                                                    selectedMediaCategories + category
                                                } else {
                                                    selectedMediaCategories - category
                                                }
                                            },
                                            enabled = !isClearingMedia,
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = primaryColor,
                                            ),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = categoryLabels.getValue(category),
                                                color = onSurfaceColor,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                text = "${usage.fileCount} • ${formatStorageSize(usage.bytes)}",
                                                color = onSurfaceVariant,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val categories = selectedMediaCategories
                                    isClearingMedia = true
                                    storageScope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                AttachmentStorageManager.clear(context, categories)
                                            }
                                            AttachmentImageCache.clear()
                                            showMediaCleanupDialog = false
                                            selectedMediaCategories = emptySet()
                                            val message = Localizations.tr(appLanguage, "Удалено: ${result.deletedFiles}" + if (result.skippedActiveTransfers > 0) ". Активные передачи пропущены" else "", "Deleted: ${result.deletedFiles}" + if (result.skippedActiveTransfers > 0) ". Active transfers were skipped" else "")
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        } catch (error: Exception) {
                                            error.printStackTrace()
                                            Toast.makeText(
                                                context,
                                                if (appLanguage == "Русский") {
                                                    "Не удалось удалить выбранные файлы"
                                                } else {
                                                    "Could not delete selected files"
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } finally {
                                            isClearingMedia = false
                                            refreshStorageSizes()
                                        }
                                    }
                                },
                                enabled = selectedMediaCategories.isNotEmpty() && !isClearingMedia,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE53935),
                                    contentColor = Color.White,
                                ),
                            ) {
                                if (isClearingMedia) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                } else {
                                    Text(
                                        if (appLanguage == "Русский") "Удалить" else "Delete",
                                    )
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showMediaCleanupDialog = false },
                                enabled = !isClearingMedia,
                            ) {
                                Text(
                                    if (appLanguage == "Русский") "Отмена" else "Cancel",
                                    color = primaryColor,
                                )
                            }
                        },
                        containerColor = surfaceColor,
                        shape = RoundedCornerShape(20.dp),
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
                        val totalBytes =
                            cacheBytes + receivedStickerBytes + avatarsBytes + logsBytes + dbBytes

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

                                // Item: received sticker cache. Installed and owned packs are excluded.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            if (appLanguage == "Русский") {
                                                "Стикеры собеседников"
                                            } else {
                                                "Peer stickers"
                                            },
                                            fontSize = 14.sp,
                                            color = onSurfaceColor,
                                        )
                                        Text(
                                            if (appLanguage == "Русский") {
                                                "Полученные в чатах, без добавленных паков"
                                            } else {
                                                "Received in chats, excluding installed packs"
                                            },
                                            fontSize = 11.sp,
                                            color = onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        if (isCalculating) "..." else {
                                            formatStorageSize(receivedStickerBytes)
                                        },
                                        fontSize = 14.sp,
                                        color = onSurfaceVariant,
                                    )
                                }

                                Text(
                                    text = if (appLanguage == "Русский") {
                                        "Лимит кэша"
                                    } else {
                                        "Cache limit"
                                    },
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                    fontSize = 12.sp,
                                    color = onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    P2PPreferences.STICKER_CACHE_LIMIT_OPTIONS_MB.forEach { limitMb ->
                                        FilterChip(
                                            selected = stickerCacheLimitMb == limitMb,
                                            onClick = {
                                                if (stickerCacheLimitMb != limitMb) {
                                                    stickerCacheLimitMb = limitMb
                                                    P2PPreferences.setStickerCacheLimitMb(
                                                        context,
                                                        limitMb,
                                                    )
                                                    storageScope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            StickerSupport.trimReceivedCache(context)
                                                        }
                                                        refreshStorageSizes()
                                                    }
                                                }
                                            },
                                            label = { Text("$limitMb MB") },
                                            leadingIcon = if (stickerCacheLimitMb == limitMb) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                    }
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

                        Text(
                            text = if (appLanguage == "Русский") {
                                "Локальные медиафайлы"
                            } else {
                                "Local media files"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    0.5.dp,
                                    onSurfaceColor.copy(alpha = 0.04f),
                                    RoundedCornerShape(16.dp),
                                ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                val categoryLabels = mapOf(
                                    AttachmentCategory.VIDEO to (
                                        if (appLanguage == "Русский") "Видео" else "Videos"
                                    ),
                                    AttachmentCategory.IMAGE to (
                                        if (appLanguage == "Русский") "Изображения" else "Images"
                                    ),
                                    AttachmentCategory.FILE to (
                                        if (appLanguage == "Русский") "Документы и файлы" else "Documents & files"
                                    ),
                                    AttachmentCategory.VOICE to (
                                        if (appLanguage == "Русский") "Голосовые сообщения" else "Voice messages"
                                    ),
                                    AttachmentCategory.STICKER to (
                                        if (appLanguage == "Русский") {
                                            "Стикеры собеседников"
                                        } else {
                                            "Peer stickers"
                                        }
                                    ),
                                )
                                AttachmentCategory.entries.forEachIndexed { index, category ->
                                    val usage = mediaUsage[category] ?: AttachmentCategoryUsage()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            categoryLabels.getValue(category),
                                            color = onSurfaceColor,
                                            fontSize = 14.sp,
                                        )
                                        Text(
                                            if (isCalculating) {
                                                "..."
                                            } else {
                                                "${usage.fileCount} • ${formatStorageSize(usage.bytes)}"
                                            },
                                            color = onSurfaceVariant,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    if (index != AttachmentCategory.entries.lastIndex) {
                                        Spacer(modifier = Modifier.height(9.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                OutlinedButton(
                                    onClick = {
                                        selectedMediaCategories = emptySet()
                                        showMediaCleanupDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isCalculating && !isClearingMedia,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        text = if (appLanguage == "Русский") {
                                            "Выбрать типы для удаления"
                                        } else {
                                            "Choose media types to delete"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        color = primaryColor,
                                    )
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
                                    enabled = !isCalculating && !isClearingMedia
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
                                HelpAccordionItem(
                                    title = Localizations.getString("help_yggdrasil_title", appLanguage),
                                    description = Localizations.getString("help_yggdrasil_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_tor_title", appLanguage),
                                    description = Localizations.getString("help_tor_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_socks5_title", appLanguage),
                                    description = Localizations.getString("help_socks5_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_relay_title", appLanguage),
                                    description = Localizations.getString("help_relay_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_e2ee_title", appLanguage),
                                    description = Localizations.getString("help_e2ee_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_privacy_title", appLanguage),
                                    description = Localizations.getString("help_privacy_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_circuit_rotation_title", appLanguage),
                                    description = Localizations.getString("help_circuit_rotation_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_sqlcipher_title", appLanguage),
                                    description = Localizations.getString("help_sqlcipher_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_duress_title", appLanguage),
                                    description = Localizations.getString("help_duress_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_onion_exchange_title", appLanguage),
                                    description = Localizations.getString("help_onion_exchange_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_transport_badges_title", appLanguage),
                                    description = Localizations.getString("help_transport_badges_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor,
                                    showDivider = false
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Section Header: Useful Tips & Best Practices
                        Text(
                            text = Localizations.getString("help_sec_tips", appLanguage),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )

                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                HelpAccordionItem(
                                    title = Localizations.getString("help_tip_duress_title", appLanguage),
                                    description = Localizations.getString("help_tip_duress_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_tip_tor_rotation_title", appLanguage),
                                    description = Localizations.getString("help_tip_tor_rotation_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_tip_bridges_title", appLanguage),
                                    description = Localizations.getString("help_tip_bridges_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor
                                )

                                HelpAccordionItem(
                                    title = Localizations.getString("help_tip_screen_security_title", appLanguage),
                                    description = Localizations.getString("help_tip_screen_security_desc", appLanguage),
                                    primaryColor = primaryColor,
                                    onSurfaceColor = onSurfaceColor,
                                    showDivider = false
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(30.dp))
                    }
                }
            }
        }
    }


    if (showFullScreenAvatar) {
        com.example.twopchat.ui.common.FullScreenAvatarViewer(
            title = username,
            bitmap = fullProfileBitmap ?: profileBitmap,
            initials = username.take(2).uppercase(),
            avatarColor = primaryColor,
            onDismiss = { showFullScreenAvatar = false }
        )
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
                            showFullScreenAvatar = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Просмотреть фото" else "View Photo",
                            color = primaryColor,
                            fontSize = 15.sp
                        )
                    }
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
                            fullProfileBitmap = null
                            sharedPrefs.edit().remove("profile_photo_uri").apply()
                            try {
                                val file = java.io.File(context.filesDir, "profile_avatar.jpg")
                                if (file.exists()) {
                                    file.delete()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            com.example.twopchat.relay.P2PMessageRelay.shareAvatarWithConnectedPeers(context)
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

    if (showLanguageDialog) {
        val languages = listOf(
            Pair("Русский", "🇷🇺"),
            Pair("English", "🇬🇧"),
            Pair("Deutsch", "🇩🇪"),
            Pair("Español", "🇪🇸"),
            Pair("Français", "🇫🇷"),
            Pair("Português", "🇵🇹")
        )
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
                    languages.forEach { (lang, flag) ->
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = flag, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(text = lang, fontSize = 15.sp, color = onSurfaceColor)
                            }
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

    // PIN Verification Dialog before showing Seed Phrase
    if (showPinForBackupDialog) {
        var verifyPin by remember { mutableStateOf("") }
        var verifyPinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showPinForBackupDialog = false
                verifyPin = ""
                verifyPinError = false
            },
            title = {
                Text(
                    text = if (appLanguage == "Русский") "Подтверждение PIN-кода" else "Confirm Passcode",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (appLanguage == "Русский") "Введите ваш PIN-код для просмотра резервной копии ключей" else "Enter your passcode to view recovery seed phrase",
                        fontSize = 13.sp,
                        color = onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = verifyPin,
                        onValueChange = {
                            if (it.length <= 12) {
                                verifyPin = it
                                verifyPinError = false
                            }
                        },
                        label = { Text("PIN") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        isError = verifyPinError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            focusedLabelColor = primaryColor
                        )
                    )
                    if (verifyPinError) {
                        Text(
                            text = if (appLanguage == "Русский") "Неверный PIN-код" else "Incorrect passcode",
                            color = Color(0xFFEF5350),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val correctPin = sharedPrefs.getString("passcode_value", "") ?: ""
                        if (SecurityUtils.verifyAndMigratePasscode(verifyPin, correctPin, sharedPrefs, "passcode_value")) {
                            showPinForBackupDialog = false
                            verifyPin = ""
                            verifyPinError = false
                            showSeedBackupDialog = true
                        } else {
                            verifyPinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (appLanguage == "Русский") "Подтвердить" else "Confirm", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinForBackupDialog = false
                    verifyPin = ""
                    verifyPinError = false
                }) {
                    Text(Localizations.getString("cancel", appLanguage), color = onSurfaceVariant)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Account Seed Phrase Backup Dialog (FLAG_SECURE + Sensitive Clipboard)
    if (showSeedBackupDialog) {
        val mnemonic = remember { com.example.twopchat.NativeBridge.getLocalSeedMnemonic() ?: "" }
        val words = remember(mnemonic) {
            if (mnemonic.isNotBlank()) mnemonic.split(" ") else emptyList()
        }

        AlertDialog(
            onDismissRequest = { showSeedBackupDialog = false },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔑", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Localizations.getString("seed_backup_dialog_title", appLanguage),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEF5350).copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("⚠️", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = Localizations.getString("seed_backup_warning", appLanguage),
                                fontSize = 12.sp,
                                color = onSurfaceColor.copy(alpha = 0.9f),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    if (words.size == 24) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = surfaceVariant.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                for (row in 0 until 12) {
                                    val idx1 = row
                                    val idx2 = row + 12
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Column 1 (Words 1-12)
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${idx1 + 1}.",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryColor,
                                                modifier = Modifier.width(24.dp)
                                            )
                                            Text(
                                                text = words[idx1],
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = onSurfaceColor,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Column 2 (Words 13-24)
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${idx2 + 1}.",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryColor,
                                                modifier = Modifier.width(24.dp)
                                            )
                                            Text(
                                                text = words[idx2],
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = onSurfaceColor,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = if (appLanguage == "Русский") "Ключ не инициализирован" else "Key not initialized",
                            color = onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (mnemonic.isNotBlank()) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("2PChat Recovery Phrase", mnemonic)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                clip.description.extras = android.os.PersistableBundle().apply {
                                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, Localizations.getString("phrase_copied", appLanguage), Toast.LENGTH_SHORT).show()

                            // Auto-clear clipboard after 45 seconds
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(45_000L)
                                runCatching {
                                    if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == mnemonic) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                            clipboard.clearPrimaryClip()
                                        } else {
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                                        }
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(Localizations.getString("copy_phrase", appLanguage), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSeedBackupDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = onSurfaceVariant)
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
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
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
                                    .putString("passcode_value", SecurityUtils.protectPasscode(pin1))
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
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
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
                        if (SecurityUtils.verifyAndMigratePasscode(enteredPin, correctPin, sharedPrefs, "passcode_value")) {
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
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
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
                                if (SecurityUtils.verifyPasscode(duressPin1, mainPinVal)) {
                                    duressMatchesMainError = true
                                    duressPin1 = ""
                                } else {
                                    isDuressConfirming = true
                                }
                            }
                        } else {
                            if (duressPin1 == duressPin2) {
                                sharedPrefs.edit()
                                    .putString("passcode_duress_value", SecurityUtils.protectPasscode(duressPin1))
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
fun SettingsSectionHeader(title: String, primaryColor: Color) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = primaryColor,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
    )
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
    useOriginalIconColors: Boolean = false,
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
                .size(38.dp)
                .background(
                    color = if (isWarning) warningRed.copy(alpha = 0.16f) else iconColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = if (useOriginalIconColors) Color.Unspecified else effectiveIconColor,
                modifier = Modifier.size(if (useOriginalIconColors) 22.dp else 19.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(14.dp))
        
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
                    color = onSurfaceVariant.copy(alpha = 0.78f)
                )
            }
        }
        
        if (value != null) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = primaryColor,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (!isWarning) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "›",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceVariant.copy(alpha = 0.35f)
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
                    contentDescription = Localizations.tr(appLanguage, "Назад", "Back", "Zurück", "Atrás", "Retour", "Voltar"),
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

data class DeepSettingItem(
    val category: String,
    val categoryColor: Color,
    val title: String,
    val subtitle: String,
    val valueBadge: String? = null,
    val keywords: List<String>,
    val onClick: () -> Unit
)

@Composable
fun DeepSearchResultRow(
    category: String,
    categoryColor: Color,
    title: String,
    subtitle: String,
    valueBadge: String? = null,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = categoryColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurfaceColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!valueBadge.isNullOrEmpty()) {
                Text(
                    text = valueBadge,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = "›",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (!valueBadge.isNullOrEmpty()) primaryColor else onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
fun LanguageSettingsPage(
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    onBackClick: () -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val languageList = remember {
        listOf(
            Triple("Русский", "Russian", "🇷🇺"),
            Triple("English", "English", "🇬🇧"),
            Triple("Deutsch", "German", "🇩🇪"),
            Triple("Español", "Spanish", "🇪🇸"),
            Triple("Français", "French", "🇫🇷"),
            Triple("Português", "Portuguese", "🇵🇹")
        )
    }

    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) languageList
        else languageList.filter {
            it.first.contains(searchQuery, ignoreCase = true) || it.second.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Top Bar Header: Back button, Title, and Search icon
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
            if (isSearching) {
                val searchContext = androidx.compose.ui.platform.LocalContext.current
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(fontSize = 15.sp, color = onSurfaceColor),
                    singleLine = true,
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = searchContext,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(surfaceColor, shape = RoundedCornerShape(12.dp))
                        .border(1.5.dp, primaryColor, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = if (appLanguage == "Русский") "Поиск языка..." else "Search language...",
                                    fontSize = 14.sp,
                                    color = onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = {
                        isSearching = false
                        searchQuery = ""
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Text(
                    text = if (appLanguage == "Русский") "Язык" else "Language",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { isSearching = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Search,
                        contentDescription = "Search",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Section Title: "Язык" / "Language"
        Text(
            text = if (appLanguage == "Русский") "Язык" else "Language",
            color = Color(0xFF00E676),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(filteredLanguages) { (nativeName, englishName, flag) ->
                val isSelected = appLanguage == nativeName
                val activeGreen = Color(0xFF00E676)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            val selectedLang = nativeName
                            onLanguageChanged(selectedLang)
                            val toastMsg = when (selectedLang) {
                                "Русский" -> "Язык изменен на Русский"
                                "Deutsch" -> "Sprache auf Deutsch geändert"
                                "Español" -> "Idioma cambiado a Español"
                                "Français" -> "Langue changée en Français"
                                "Português" -> "Idioma alterado para Português"
                                else -> "Language changed to $englishName"
                            }
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Radio button matching design
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) activeGreen else onSurfaceVariant.copy(alpha = 0.45f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(activeGreen, CircleShape)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Text(text = flag, fontSize = 22.sp)

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = nativeName,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = onSurfaceColor
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = englishName,
                            fontSize = 12.sp,
                            color = onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }
    }
}


private data class ThemePreset(
    val id: String,
    val titleRu: String,
    val titleEn: String,
    val isDark: Boolean,
    val useCerulean: Boolean = false,
    val accentScheme: String = if (useCerulean) "cerulean" else "mint",
    val useAmoled: Boolean,
    val previewBg: Color,
    val previewIncomingBg: Color,
    val previewIncomingText: Color,
    val previewOutgoingBg: Color,
    val previewOutgoingText: Color,
)

@Composable
private fun VisualThemeSelector(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    accentScheme: String = if (useCerulean) "cerulean" else "mint",
    onAccentSchemeChanged: (String) -> Unit = {},
    useAmoled: Boolean,
    onAmoledChanged: (Boolean) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    val currentScheme = if (accentScheme.isNotBlank()) accentScheme else if (useCerulean) "cerulean" else "mint"

    val presets = remember(isDarkTheme, useAmoled) {
        listOf(
            ThemePreset(
                id = "mint",
                titleRu = "Mint Green",
                titleEn = "Mint Green",
                isDark = isDarkTheme,
                accentScheme = "mint",
                useAmoled = useAmoled,
                previewBg = when {
                    !isDarkTheme -> Color(0xFFF8FAFC)
                    useAmoled -> Color(0xFF000000)
                    else -> Color(0xFF0D131D)
                },
                previewIncomingBg = when {
                    !isDarkTheme -> Color(0xFFE2E8F0)
                    useAmoled -> Color(0xFF171717)
                    else -> Color(0xFF1E293B)
                },
                previewIncomingText = when {
                    !isDarkTheme -> Color(0xFF0F172A)
                    else -> Color(0xFFF8FAFC)
                },
                previewOutgoingBg = if (!isDarkTheme) Color(0xFF10B981) else Color(0xFF00E5A3),
                previewOutgoingText = if (!isDarkTheme) Color(0xFFFFFFFF) else Color(0xFF000000),
            ),
            ThemePreset(
                id = "cerulean",
                titleRu = "Cerulean Blue",
                titleEn = "Cerulean Blue",
                isDark = isDarkTheme,
                accentScheme = "cerulean",
                useAmoled = useAmoled,
                previewBg = when {
                    !isDarkTheme -> Color(0xFFF0F4F8)
                    useAmoled -> Color(0xFF000000)
                    else -> Color(0xFF0B141F)
                },
                previewIncomingBg = when {
                    !isDarkTheme -> Color(0xFFE2E8F0)
                    useAmoled -> Color(0xFF171717)
                    else -> Color(0xFF1C2735)
                },
                previewIncomingText = when {
                    !isDarkTheme -> Color(0xFF0F172A)
                    else -> Color(0xFFF8FAFC)
                },
                previewOutgoingBg = Color(0xFF007AFF),
                previewOutgoingText = Color(0xFFFFFFFF),
            ),
            ThemePreset(
                id = "purple",
                titleRu = "Amethyst Purple",
                titleEn = "Amethyst Purple",
                isDark = isDarkTheme,
                accentScheme = "purple",
                useAmoled = useAmoled,
                previewBg = when {
                    !isDarkTheme -> Color(0xFFF8F5FC)
                    useAmoled -> Color(0xFF000000)
                    else -> Color(0xFF0D121B)
                },
                previewIncomingBg = when {
                    !isDarkTheme -> Color(0xFFE2E8F0)
                    useAmoled -> Color(0xFF171717)
                    else -> Color(0xFF1E2536)
                },
                previewIncomingText = when {
                    !isDarkTheme -> Color(0xFF0F172A)
                    else -> Color(0xFFF8FAFC)
                },
                previewOutgoingBg = if (!isDarkTheme) Color(0xFF9333EA) else Color(0xFFBF5AF2),
                previewOutgoingText = Color(0xFFFFFFFF),
            ),
            ThemePreset(
                id = "amber",
                titleRu = "Solar Amber",
                titleEn = "Solar Amber",
                isDark = isDarkTheme,
                accentScheme = "amber",
                useAmoled = useAmoled,
                previewBg = when {
                    !isDarkTheme -> Color(0xFFFAF9F5)
                    useAmoled -> Color(0xFF000000)
                    else -> Color(0xFF0E131C)
                },
                previewIncomingBg = when {
                    !isDarkTheme -> Color(0xFFE2E8F0)
                    useAmoled -> Color(0xFF171717)
                    else -> Color(0xFF1E2736)
                },
                previewIncomingText = when {
                    !isDarkTheme -> Color(0xFF0F172A)
                    else -> Color(0xFFF8FAFC)
                },
                previewOutgoingBg = if (!isDarkTheme) Color(0xFFD97706) else Color(0xFFFF9500),
                previewOutgoingText = if (!isDarkTheme) Color(0xFFFFFFFF) else Color(0xFF000000),
            ),
        )
    }

    val activePreset = presets.firstOrNull { it.accentScheme == currentScheme } ?: presets.first()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live Chat Interactive Preview Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = activePreset.previewBg),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = primaryColor.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header badge inside live preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(activePreset.previewIncomingBg.copy(alpha = 0.75f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Предпросмотр темы" else "Theme Live Preview",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = activePreset.previewIncomingText.copy(alpha = 0.85f)
                        )
                    }
                }

                // Incoming Message Bubble
                Box(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 2.dp, bottomEnd = 14.dp))
                        .background(activePreset.previewIncomingBg)
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Column {
                        Text(
                            text = if (appLanguage == "Русский") "Посмотри, как тебе такой вариант оформления?" else "How do you like this chat style?",
                            fontSize = 12.sp,
                            color = activePreset.previewIncomingText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "04:25",
                            fontSize = 9.sp,
                            color = activePreset.previewIncomingText.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }

                // Outgoing Message Bubble
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 2.dp))
                        .background(activePreset.previewOutgoingBg)
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Column {
                        Text(
                            text = if (appLanguage == "Русский") "Выглядит отлично, оставляем!" else "Looks awesome, let's keep it!",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = activePreset.previewOutgoingText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "04:25 ✓✓",
                            fontSize = 9.sp,
                            color = activePreset.previewOutgoingText.copy(alpha = 0.75f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }

        // Horizontal Theme Cards Carousel
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (appLanguage == "Русский") "Готовые темы" else "Theme Presets",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurfaceColor
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = preset.accentScheme == currentScheme
                    val title = if (appLanguage == "Русский") preset.titleRu else preset.titleEn

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(108.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                onAccentSchemeChanged(preset.accentScheme)
                            }
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .background(surfaceColor)
                            .padding(8.dp)
                    ) {
                        // Mini Theme Preview Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(preset.previewBg)
                                .padding(5.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Mini incoming bubble
                                Box(
                                    modifier = Modifier
                                        .width(46.dp)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(preset.previewIncomingBg)
                                )
                                // Mini outgoing bubble
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .width(52.dp)
                                        .height(15.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(preset.previewOutgoingBg)
                                )
                            }

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(primaryColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) primaryColor else onSurfaceColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Color Accent Swatches & Quick Mode Selectors
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(onSurfaceColor.copy(alpha = 0.03f))
                .padding(12.dp)
        ) {
            Text(
                text = if (appLanguage == "Русский") "Цветовая схема" else "Color Accent",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurfaceColor
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mint Green Swatch
                    val isMint = currentScheme == "mint"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onAccentSchemeChanged("mint")
                            }
                            .border(
                                width = if (isMint) 2.dp else 1.dp,
                                color = if (isMint) Color(0xFF00E5A3) else onSurfaceColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .background(surfaceColor)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5A3))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Mint Green",
                            fontSize = 12.sp,
                            fontWeight = if (isMint) FontWeight.Bold else FontWeight.Medium,
                            color = onSurfaceColor
                        )
                    }

                    // Cerulean Blue Swatch
                    val isBlue = currentScheme == "cerulean"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onAccentSchemeChanged("cerulean")
                            }
                            .border(
                                width = if (isBlue) 2.dp else 1.dp,
                                color = if (isBlue) Color(0xFF007AFF) else onSurfaceColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .background(surfaceColor)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF007AFF))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cerulean Blue",
                            fontSize = 12.sp,
                            fontWeight = if (isBlue) FontWeight.Bold else FontWeight.Medium,
                            color = onSurfaceColor
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Amethyst Purple Swatch
                    val isPurple = currentScheme == "purple"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onAccentSchemeChanged("purple")
                            }
                            .border(
                                width = if (isPurple) 2.dp else 1.dp,
                                color = if (isPurple) Color(0xFFBF5AF2) else onSurfaceColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .background(surfaceColor)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFBF5AF2))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Amethyst Purple",
                            fontSize = 12.sp,
                            fontWeight = if (isPurple) FontWeight.Bold else FontWeight.Medium,
                            color = onSurfaceColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Solar Amber Swatch
                    val isAmber = currentScheme == "amber"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onAccentSchemeChanged("amber")
                            }
                            .border(
                                width = if (isAmber) 2.dp else 1.dp,
                                color = if (isAmber) Color(0xFFFF9500) else onSurfaceColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .background(surfaceColor)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF9500))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Solar Amber",
                            fontSize = 12.sp,
                            fontWeight = if (isAmber) FontWeight.Bold else FontWeight.Medium,
                            color = onSurfaceColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

            // Quick Switches for Light Mode & AMOLED
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Localizations.getString("light_theme", appLanguage),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurfaceColor
                    )
                    Text(
                        text = Localizations.getString("light_theme_desc", appLanguage),
                        fontSize = 11.sp,
                        color = onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = !isDarkTheme,
                    onCheckedChange = { onThemeChanged(!it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = primaryColor,
                        checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                    )
                )
            }

            if (isDarkTheme) {
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Localizations.getString("amoled_theme", appLanguage),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceColor
                        )
                        Text(
                            text = Localizations.getString("amoled_theme_desc", appLanguage),
                            fontSize = 11.sp,
                            color = onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = useAmoled,
                        onCheckedChange = { onAmoledChanged(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = primaryColor,
                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpAccordionItem(
    title: String,
    description: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    showDivider: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "accordionChevronRotation"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = primaryColor,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotationAngle)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(150))
        ) {
            Text(
                text = description,
                fontSize = 13.sp,
                color = onSurfaceColor.copy(alpha = 0.85f),
                lineHeight = 20.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp)
            )
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = onSurfaceColor.copy(alpha = 0.08f)
            )
        }
    }
}




