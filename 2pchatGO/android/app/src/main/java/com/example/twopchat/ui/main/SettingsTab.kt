package com.example.twopchat.ui.main

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.theme.*
import com.example.twopchat.tor.TorManager
import com.example.twopchat.update.AppUpdateManager
import com.example.twopchat.update.ReleaseInfo
import com.example.twopchat.update.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val sharedPrefs = remember { P2PPreferences.prefs(context) }

    // App update states
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var isDownloadingApk by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }
    
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
    var aboutMeText by remember { mutableStateOf(P2PPreferences.aboutMe(context)) }
    var showEditAboutMeDialog by remember { mutableStateOf(false) }
    
    var showDisguiseInstructionDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSetPasscodeDialog by remember { mutableStateOf(false) }
    var showDisablePasscodeDialog by remember { mutableStateOf(false) }
    var showAutolockDialog by remember { mutableStateOf(false) }
    var autolockMinutes by remember { mutableStateOf(sharedPrefs.getInt("passcode_autolock_minutes", 1)) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showSetDuressDialog by remember { mutableStateOf(false) }
    var showSeedBackupDialog by remember { mutableStateOf(false) }
    var showPinForBackupDialog by remember { mutableStateOf(false) }

    var isSearchingSettings by remember { mutableStateOf(false) }
    var settingsSearchQuery by remember { mutableStateOf("") }
    val isTorRunning by TorManager.isTorRunning.collectAsState()
    val availableUpdateRelease by AppUpdateManager.availableUpdateRelease.collectAsState()

    LaunchedEffect(Unit) {
        AppUpdateManager.initUpdateState(context)
        AppUpdateManager.checkForUpdatesSilently(context)
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
                    AnimatedContent(
                        targetState = isSearchingSettings,
                        transitionSpec = {
                            (fadeIn(animationSpec = com.example.twopchat.theme.MotionTokens.FastTween) + expandVertically(expandFrom = Alignment.Top, animationSpec = com.example.twopchat.theme.MotionTokens.ResponsiveIntSizeSpring))
                                .togetherWith(fadeOut(animationSpec = com.example.twopchat.theme.MotionTokens.FastTween) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = com.example.twopchat.theme.MotionTokens.FastIntSizeTween))
                        },
                        label = "settings_search_bar_transition",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp)
                    ) { isSearching ->
                        if (!isSearching) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                    val incognitoKeyboard = remember { P2PPreferences.isIncognitoKeyboardEnabled(context) }

                    val deepSettingsList = remember(appLanguage, hasPasscode, hasDuressPIN, allowScreenshots, incognitoKeyboard) {
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
                                    val logFile = File(File(context.filesDir, "config"), "app.log")
                                    if (logFile.exists() && logFile.length() > 0) {
                                        try {
                                            val authority = "${context.packageName}.fileprovider"
                                            val fileUri: android.net.Uri = FileProvider.getUriForFile(context, authority, logFile)
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, fileUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, if (isRu) "Поделиться логами" else "Share Logs"))
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

                    AnimatedVisibility(
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
                        // Hero Profile Card
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
                                        // Camera badge
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
                                                tint = if (primaryColor == MintGreen) StealthBlack else Color.White,
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

                        // Group 1: 🛡 АНОНИМНОСТЬ И TOR
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

                        // Group 2: 🔒 БЕЗОПАСНОСТЬ И ДОСТУП
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🔒 БЕЗОПАСНОСТЬ", "🔒 SECURITY", "🔒 SICHERHEIT", "🔒 SEGURIDAD", "🔒 SÉCURITÉ", "🔒 SEGURANÇA"),
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
                                    title = Localizations.getString("security_and_keys", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Личный ключ, отпечаток (Fingerprint) и сверка" else "Personal key, fingerprint and identity verification",
                                    value = if (localFingerprint.isNotBlank()) localFingerprint.take(6) + "…" else null,
                                    iconRes = com.example.twopchat.R.drawable.ic_copy_key,
                                    iconColor = Color(0xFF10B981),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "security" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("passcode_lock", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "4-значный PIN-код и тревожный пароль (Duress)" else "4-digit PIN lock and Duress emergency wipe",
                                    value = if (hasPasscode) (if (appLanguage == "Русский") "Включен" else "ON") else null,
                                    iconRes = com.example.twopchat.R.drawable.ic_shield_status,
                                    iconColor = Color(0xFF3B82F6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "security" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("privacy_protection", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Блокировка скриншотов и клавиатура инкогнито" else "Screen capture blocking & incognito keyboard",
                                    iconRes = com.example.twopchat.R.drawable.ic_shield_status,
                                    iconColor = Color(0xFF06B6D4),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "security" }
                                )
                            }
                        }

                        // Group 3: 🌐 СЕТЬ И P2P
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🌐 СЕТЬ И P2P", "🌐 NETWORK & P2P", "🌐 NETZWERK & P2P", "🌐 RED Y P2P", "🌐 RÉSEAU & P2P", "🌐 REDE E P2P"),
                            Color(0xFF06B6D4)
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
                                    title = Localizations.getString("yggdrasil_network", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "IPv6 mesh-сеть, публичные и пользовательские пиры" else "IPv6 mesh network, public and custom peers",
                                    iconRes = com.example.twopchat.R.drawable.ic_quick_link,
                                    iconColor = Color(0xFFA855F7),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "yggdrasil_peers" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("trackers_and_dht", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "DHT-обнаружение, UDP/HTTP трекеры и SOCKS5" else "DHT discovery, UDP/HTTP trackers and SOCKS5",
                                    iconRes = com.example.twopchat.R.drawable.ic_quick_ip,
                                    iconColor = Color(0xFF0EA5E9),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "trackers" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("advanced_network_params", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Порт P2P, Wi-Fi discovery, IPv4 и UPnP" else "P2P port, Wi-Fi discovery, IPv4 and UPnP NAT",
                                    iconRes = com.example.twopchat.R.drawable.ic_menu_settings,
                                    iconColor = Color(0xFF14B8A6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "advanced_network" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("network_data_usage", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Статистика входящего и исходящего P2P трафика" else "Inbound and outbound P2P traffic statistics",
                                    iconRes = com.example.twopchat.R.drawable.ic_send_airplane,
                                    iconColor = Color(0xFF3B82F6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "network_usage" }
                                )
                            }
                        }

                        // Group 4: 🎨 ЧАТЫ И ОФОРМЛЕНИЕ
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "🎨 ЧАТЫ И ОФОРМЛЕНИЕ", "🎨 CHATS & APPEARANCE", "🎨 CHATS & DESIGN", "🎨 CHATS Y APARIENCIA", "🎨 DISCUSSIONS & THÈME", "🎨 CONVERSAS E APARÊNCIA"),
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
                                    title = Localizations.getString("appearance_and_themes", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Цветовая схема, светлая тема, AMOLED и иконка" else "Color accent, light mode, AMOLED and launcher icon",
                                    value = if (!isDarkTheme) "Light" else if (useAmoled) "AMOLED" else if (accentScheme == "purple") "Amethyst" else if (accentScheme == "amber") "Amber" else if (useCerulean || accentScheme == "cerulean") "Cerulean" else "Mint",
                                    iconRes = com.example.twopchat.R.drawable.ic_chat_wallpaper,
                                    iconColor = Color(0xFFF59E0B),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "chat_settings" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("stickers_and_emoji", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Создание наклеек, Telegram .tgs и управление паками" else "Custom stickers, Telegram .tgs packs & editor",
                                    iconRes = com.example.twopchat.R.drawable.ic_sticker_smile,
                                    iconColor = Color(0xFFEC4899),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "sticker_packs" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("notifications_and_sounds", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Звуковые сигналы, вибрация и превью сообщений" else "Push alerts, vibration and message previews",
                                    iconRes = com.example.twopchat.R.drawable.ic_notifications,
                                    iconColor = Color(0xFFEF4444),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "notifications" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                SettingsRow(
                                    title = Localizations.getString("data_and_storage", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Использование памяти и очистка кэша файлов" else "Storage breakdown & cache cleanup manager",
                                    iconRes = com.example.twopchat.R.drawable.ic_database_storage,
                                    iconColor = Color(0xFF10B981),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "storage" }
                                )
                            }
                        }

                        // Group 5: ⚙️ ПРИЛОЖЕНИЕ И СПРАВКА
                        SettingsSectionHeader(
                            Localizations.tr(appLanguage, "⚙️ ПРИЛОЖЕНИЕ", "⚙️ APPLICATION", "⚙️ ANWENDUNG", "⚙️ APLICACIÓN", "⚙️ APPLICATION", "⚙️ APLICATIVO"),
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
                                    title = Localizations.getString("app_language", appLanguage),
                                    subtitle = if (appLanguage == "Русский") "Русский, English, Deutsch, Español..." else "Russian, English, German, Spanish...",
                                    value = appLanguage,
                                    iconRes = com.example.twopchat.R.drawable.ic_language_translate,
                                    iconColor = Color(0xFF8B5CF6),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    onClick = { activeSubPage = "language" }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

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
                                        val logFile = File(File(context.filesDir, "config"), "app.log")
                                        if (logFile.exists() && logFile.length() > 0) {
                                            try {
                                                val authority = "${context.packageName}.fileprovider"
                                                val fileUri: android.net.Uri = FileProvider.getUriForFile(context, authority, logFile)
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, fileUri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, if (appLanguage == "Русский") "Поделиться логами" else "Share Logs"))
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error sharing logs: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, if (appLanguage == "Русский") "Лог-файл пуст или еще не создан" else "Log file is empty or not created yet", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                val currentVerName = remember { AppUpdateManager.getCurrentVersionName(context) }
                                val hasUpdate = availableUpdateRelease != null
                                SettingsRow(
                                    title = if (appLanguage == "Русский") "Проверить обновления" else "Check for Updates",
                                    subtitle = if (isCheckingUpdate) {
                                        if (appLanguage == "Русский") "Проверка релизов на GitHub..." else "Checking GitHub releases..."
                                    } else if (hasUpdate) {
                                        if (appLanguage == "Русский") "Доступна новая версия v${availableUpdateRelease?.versionName} • Нажмите для установки"
                                        else "New version v${availableUpdateRelease?.versionName} available • Tap to install"
                                    } else {
                                        if (appLanguage == "Русский") "Текущая версия: v$currentVerName" else "Current version: v$currentVerName"
                                    },
                                    value = if (isCheckingUpdate) "..." else null,
                                    iconRes = com.example.twopchat.R.drawable.ic_app_update,
                                    iconColor = if (hasUpdate) Color(0xFFFF3B30) else Color(0xFF10B981),
                                    onSurfaceColor = onSurfaceColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    primaryColor = primaryColor,
                                    hasNotificationDot = hasUpdate,
                                    badgeLabel = if (hasUpdate) "NEW" else null,
                                    onClick = {
                                        if (!isCheckingUpdate) {
                                            val release = availableUpdateRelease
                                            if (release != null) {
                                                updateResult = com.example.twopchat.update.UpdateCheckResult.UpdateAvailable(
                                                    release,
                                                    currentVerName
                                                )
                                                showUpdateDialog = true
                                            } else {
                                                isCheckingUpdate = true
                                                coroutineScope.launch {
                                                    val result = AppUpdateManager.checkLatestRelease(context)
                                                    updateResult = result
                                                    isCheckingUpdate = false
                                                    showUpdateDialog = true
                                                }
                                            }
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
                        val footerVerName = remember { AppUpdateManager.getCurrentVersionName(context) }
                        Text(
                            text = "2PChat v$footerVerName • Go Core v1.5 (P2P)",
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
            "chat_settings" -> AppearanceSettingsPage(
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged,
                useCerulean = useCerulean,
                onAccentChanged = onAccentChanged,
                accentScheme = accentScheme,
                onAccentSchemeChanged = onAccentSchemeChanged,
                useAmoled = useAmoled,
                onAmoledChanged = onAmoledChanged,
                activeIconAlias = activeIconAlias,
                onIconChanged = onIconChanged,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = { activeSubPage = null }
            )
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
            "security" -> SecuritySettingsPage(
                localFingerprint = localFingerprint,
                formattedLocalFingerprint = formattedLocalFingerprint,
                activeIconAlias = activeIconAlias,
                onIconChanged = onIconChanged,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                surfaceVariant = surfaceVariant,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = { activeSubPage = null },
                onRequestDisguiseDialog = { showDisguiseInstructionDialog = true },
                onRequestPasscodeDialog = { showSetPasscodeDialog = true },
                onRequestDisablePasscodeDialog = { showDisablePasscodeDialog = true },
                onRequestAutolockDialog = { showAutolockDialog = true },
                onRequestDuressDialog = { showSetDuressDialog = true },
                onRequestSeedBackupDialog = { showSeedBackupDialog = true },
                onRequestPinForBackupDialog = { showPinForBackupDialog = true }
            )
            "advanced_network" -> AdvancedNetworkSettingsPage(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = { activeSubPage = null }
            )
            "notifications" -> NotificationsSettingsPage(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = { activeSubPage = null }
            )
            "storage" -> StorageSettingsPage(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = { activeSubPage = null }
            )
            "help_reference" -> HelpReferencePage(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onBackClick = { activeSubPage = null }
            )
        }
    }

    SettingsModalsAndDialogs(
        appLanguage = appLanguage,
        primaryColor = primaryColor,
        surfaceColor = surfaceColor,
        surfaceVariant = surfaceVariant,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        username = username,
        profileBitmap = profileBitmap,
        fullProfileBitmap = fullProfileBitmap,
        onPickNewAvatar = { imagePickerLauncher.launch("image/*") },
        onRemoveAvatar = {
            profilePhotoUri = null
            profileBitmap = null
            fullProfileBitmap = null
            sharedPrefs.edit().remove("profile_photo_uri").apply()
            try {
                val file = File(context.filesDir, "profile_avatar.jpg")
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            com.example.twopchat.relay.P2PMessageRelay.shareAvatarWithConnectedPeers(context)
            Toast.makeText(context, if (appLanguage == "Русский") "Фото профиля удалено" else "Profile photo removed", Toast.LENGTH_SHORT).show()
        },
        showFullScreenAvatar = showFullScreenAvatar,
        onDismissFullScreenAvatar = { showFullScreenAvatar = false },
        showAvatarOptions = showAvatarOptions,
        onDismissAvatarOptions = { showAvatarOptions = false },
        onOpenFullScreenAvatar = { showFullScreenAvatar = true },
        showLanguageDialog = showLanguageDialog,
        onDismissLanguageDialog = { showLanguageDialog = false },
        onLanguageChanged = onLanguageChanged,
        showDisguiseInstructionDialog = showDisguiseInstructionDialog,
        onDismissDisguiseInstructionDialog = { showDisguiseInstructionDialog = false },
        showPinForBackupDialog = showPinForBackupDialog,
        onDismissPinForBackupDialog = { showPinForBackupDialog = false },
        onPinForBackupConfirmed = { showSeedBackupDialog = true },
        showSeedBackupDialog = showSeedBackupDialog,
        onDismissSeedBackupDialog = { showSeedBackupDialog = false },
        showSetPasscodeDialog = showSetPasscodeDialog,
        onDismissSetPasscodeDialog = { showSetPasscodeDialog = false },
        onPasscodeSetSuccess = {},
        showDisablePasscodeDialog = showDisablePasscodeDialog,
        onDismissDisablePasscodeDialog = { showDisablePasscodeDialog = false },
        onPasscodeDisabledSuccess = {},
        showAutolockDialog = showAutolockDialog,
        onDismissAutolockDialog = { showAutolockDialog = false },
        autolockMinutes = autolockMinutes,
        onAutolockMinutesChanged = { autolockMinutes = it },
        showDeleteAccountDialog = showDeleteAccountDialog,
        onDismissDeleteAccountDialog = { showDeleteAccountDialog = false },
        onDeleteAccountConfirmed = onDeleteAccount,
        showSetDuressDialog = showSetDuressDialog,
        onDismissSetDuressDialog = { showSetDuressDialog = false },
        showEditAboutMeDialog = showEditAboutMeDialog,
        onDismissEditAboutMeDialog = { showEditAboutMeDialog = false },
        aboutMeText = aboutMeText,
        onAboutMeSaved = { aboutMeText = it }
    )

    if (showUpdateDialog) {
        val res = updateResult
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingApk) {
                    showUpdateDialog = false
                    updateResult = null
                }
            },
            containerColor = surfaceColor,
            title = {
                Text(
                    text = when (res) {
                        is UpdateCheckResult.UpdateAvailable -> if (appLanguage == "Русский") "Доступно обновление" else "Update Available"
                        is UpdateCheckResult.UpToDate -> if (appLanguage == "Русский") "У вас последняя версия" else "Up to date"
                        is UpdateCheckResult.Error -> if (appLanguage == "Русский") "Ошибка проверки" else "Check Failed"
                        null -> ""
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = onSurfaceColor
                )
            },
            text = {
                Column {
                    when (res) {
                        is UpdateCheckResult.UpdateAvailable -> {
                            val rel = res.release
                            val sizeMb = if (rel.apkSizeBytes > 0) " (%.1f MB)".format(rel.apkSizeBytes / (1024.0 * 1024.0)) else ""
                            Text(
                                text = if (appLanguage == "Русский") "Новая версия: v${rel.versionName}$sizeMb\nТекущая версия: v${res.currentVersion}" else "New version: v${rel.versionName}$sizeMb\nCurrent version: v${res.currentVersion}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = primaryColor
                            )
                            if (rel.changelog.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (appLanguage == "Русский") "Что нового:" else "What's new:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = onSurfaceColor
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 160.dp)
                                        .background(onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = rel.changelog,
                                        fontSize = 12.sp,
                                        color = onSurfaceVariant
                                    )
                                }
                            }
                            if (isDownloadingApk) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = primaryColor,
                                    trackColor = onSurfaceColor.copy(alpha = 0.1f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = downloadStatusText,
                                    fontSize = 11.sp,
                                    color = onSurfaceVariant
                                )
                            }
                        }
                        is UpdateCheckResult.UpToDate -> {
                            Text(
                                text = if (appLanguage == "Русский") "Установлена актуальная версия v${res.currentVersion}. Обновлений не найдено." else "Installed version v${res.currentVersion} is up to date.",
                                fontSize = 14.sp,
                                color = onSurfaceVariant
                            )
                        }
                        is UpdateCheckResult.Error -> {
                            Text(
                                text = if (appLanguage == "Русский") "Не удалось проверить обновления:\n${res.message}" else "Failed to check for updates:\n${res.message}",
                                fontSize = 13.sp,
                                color = onSurfaceVariant
                            )
                        }
                        null -> {}
                    }
                }
            },
            confirmButton = {
                when (res) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        Button(
                            onClick = {
                                if (!isDownloadingApk) {
                                    if (!AppUpdateManager.canInstallPackages(context)) {
                                        Toast.makeText(
                                            context,
                                            if (appLanguage == "Русский") "Разрешите установку неизвестных приложений" else "Please allow installing unknown apps",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        AppUpdateManager.openInstallPermissionSettings(context)
                                    } else {
                                        isDownloadingApk = true
                                        downloadProgress = 0f
                                        downloadStatusText = if (appLanguage == "Русский") "Подготовка загрузки..." else "Preparing download..."
                                        coroutineScope.launch {
                                            val downloadResult = AppUpdateManager.downloadApk(context, res.release.apkUrl) { bytes, total, prog ->
                                                downloadProgress = prog
                                                val mbDown = bytes / (1024.0 * 1024.0)
                                                val mbTotal = total / (1024.0 * 1024.0)
                                                downloadStatusText = "%.1f / %.1f MB (%.0f%%)".format(mbDown, mbTotal, prog * 100)
                                            }
                                            isDownloadingApk = false
                                            downloadResult.onSuccess { apkFile ->
                                                showUpdateDialog = false
                                                updateResult = null
                                                val installed = AppUpdateManager.installApk(context, apkFile)
                                                if (!installed) {
                                                    Toast.makeText(context, "Failed to launch installer", Toast.LENGTH_SHORT).show()
                                                }
                                            }.onFailure { err ->
                                                downloadStatusText = err.localizedMessage ?: "Download failed"
                                                Toast.makeText(context, "Download failed: ${err.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isDownloadingApk,
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                        ) {
                            Text(
                                text = if (isDownloadingApk) (if (appLanguage == "Русский") "Загрузка..." else "Downloading...") else (if (appLanguage == "Русский") "Скачать и установить" else "Download & Install"),
                                color = Color.White
                            )
                        }
                    }
                    else -> {
                        TextButton(
                            onClick = {
                                showUpdateDialog = false
                                updateResult = null
                            }
                        ) {
                            Text(
                                text = if (appLanguage == "Русский") "Понятно" else "OK",
                                color = primaryColor
                            )
                        }
                    }
                }
            },
            dismissButton = {
                if (res is UpdateCheckResult.UpdateAvailable && !isDownloadingApk) {
                    TextButton(
                        onClick = {
                            showUpdateDialog = false
                            updateResult = null
                        }
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Позже" else "Later",
                            color = onSurfaceVariant
                        )
                    }
                }
            }
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
    hasNotificationDot: Boolean = false,
    badgeLabel: String? = null,
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
            if (hasNotificationDot) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 1.dp, y = (-1).dp)
                        .background(Color(0xFFFF3B30), shape = CircleShape)
                        .border(1.5.dp, Color.White, shape = CircleShape)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(14.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = effectiveOnSurfaceColor
                )
                if (hasNotificationDot) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFFFF3B30), shape = CircleShape)
                    )
                    if (badgeLabel != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFF3B30).copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF3B30)
                            )
                        }
                    }
                }
            }
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = onSurfaceVariant.copy(alpha = 0.78f),
                    fontWeight = FontWeight.Normal
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
