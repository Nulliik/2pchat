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
                                    text = Localizations.tr(appLanguage, "Настройки", "Settings", "Einstellungen", "Ajustes", "Paramètres", "Configurações", tr = "Ayarlar"),
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
                                        Localizations.tr(appLanguage, "Поиск по настройкам...", "Search settings...", "Einstellungen suchen...", "Buscar ajustes...", "Rechercher dans les paramètres...", "Buscar configurações...", tr = "Ayarlarda ara..."),
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

                    val trimmedQuery = settingsSearchQuery.trim().lowercase()

                    val hasPasscode = remember(showSetPasscodeDialog, showDisablePasscodeDialog) { sharedPrefs.contains("passcode_value") }
                    val hasDuressPIN = remember(showSetDuressDialog) { sharedPrefs.contains("passcode_duress_value") }
                    val allowScreenshots = remember { sharedPrefs.getBoolean("allow_screenshots", false) }
                    val incognitoKeyboard = remember { P2PPreferences.isIncognitoKeyboardEnabled(context) }

                    val deepSettingsList = remember(appLanguage, hasPasscode, hasDuressPIN, allowScreenshots, incognitoKeyboard) {
                        listOf(
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Оформление", en = "Appearance", de = "Erscheinungsbild", es = "Apariencia", fr = "Apparence", pt = "Aparência", tr = "Görünüm"),
                                categoryColor = Color(0xFFFFA726),
                                title = Localizations.tr(appLanguage, ru = "Тема приложения", en = "App Theme", de = "App-Design", es = "Tema de la aplicación", fr = "Thème de l'application", pt = "Tema do aplicativo", tr = "Uygulama Teması"),
                                subtitle = Localizations.tr(appLanguage, ru = "Светлая тема, цвет акцента, AMOLED", en = "Light mode, accent color, AMOLED", de = "Heller Modus, Farbackzent, AMOLED", es = "Modo claro, acento de color, AMOLED", fr = "Mode clair, accent de couleur, AMOLED", pt = "Modo claro, acento de cor, AMOLED", tr = "Açık tema, renk vurgusu, AMOLED"),
                                valueBadge = if (appLanguage == "Русский") "Настройки" else if (appLanguage == "Türkçe") "Açık" else "Light",
                                keywords = listOf("app theme", "theme", "тема", "оформление", "light", "dark", "amoled", "stealth", "акцент", "цвет", "tema", "renk", "görünüm"),
                                onClick = { activeSubPage = "chat_settings" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Оформление", en = "Appearance", de = "Erscheinungsbild", es = "Apariencia", fr = "Apparence", pt = "Aparência", tr = "Görünüm"),
                                categoryColor = Color(0xFFFFA726),
                                title = Localizations.tr(appLanguage, ru = "Иконка приложения", en = "App Launcher Icon", de = "App-Symbol", es = "Icono de la aplicación", fr = "Icône de l'application", pt = "Ícone do aplicativo", tr = "Uygulama Simgesi"),
                                subtitle = Localizations.tr(appLanguage, ru = "Выберите стиль иконки на рабочем столе", en = "Select a style for your home screen app icon", de = "Wählen Sie ein Symbol für den Startbildschirm", es = "Elige un estilo para el icono de inicio", fr = "Choisissez un style pour l'icône d'accueil", pt = "Escolha um estilo para o ícone da tela inicial", tr = "Ana ekran uygulama simgesi için bir stil seçin"),
                                valueBadge = if (appLanguage == "Русский") "Выбрать" else if (appLanguage == "Türkçe") "Seç" else "Select",
                                keywords = listOf("app launcher icon", "app icon", "icon", "иконка", "значок", "mint classic", "launcher", "иконки", "simge", "ikon"),
                                onClick = { activeSubPage = "chat_settings" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Оформление", en = "Appearance", de = "Erscheinungsbild", es = "Apariencia", fr = "Apparence", pt = "Aparência", tr = "Görünüm"),
                                categoryColor = Color(0xFFFF7043),
                                title = Localizations.tr(appLanguage, ru = "Стикерпаки", en = "Sticker Packs", de = "Sticker-Pakete", es = "Paquetes de stickers", fr = "Packs d'autocollants", pt = "Pacotes de figurinhas", tr = "Çıkartma Paketleri"),
                                subtitle = Localizations.tr(appLanguage, ru = "Создание, импорт из Telegram и управление наклейками", en = "Create, import from Telegram & manage sticker packs", de = "Erstellen, aus Telegram importieren & Sticker-Pakete verwalten", es = "Crear, importar de Telegram y administrar paquetes de stickers", fr = "Créer, importer depuis Telegram et gérer les packs d'autocollants", pt = "Criar, importar do Telegram e gerenciar pacotes de figurinhas", tr = "Çıkartma paketleri oluşturma, Telegram'dan içe aktarma ve yönetme"),
                                keywords = listOf("sticker", "stickers", "стикерпак", "стикеры", "паки", "наклейки", "telegram", "çıkartma", "çıkartmalar"),
                                onClick = { activeSubPage = "sticker_packs" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Безопасность", en = "Security", de = "Sicherheit", es = "Seguridad", fr = "Sécurité", pt = "Segurança", tr = "Güvenlik"),
                                categoryColor = Color(0xFF66BB6A),
                                title = Localizations.tr(appLanguage, ru = "Личный ключ безопасности", en = "Personal Security Key", de = "Persönlicher Sicherheitsschlüssel", es = "Clave de seguridad personal", fr = "Clé de sécurité personnelle", pt = "Chave de segurança pessoal", tr = "Kişisel Güvenlik Anahtarı"),
                                subtitle = Localizations.tr(appLanguage, ru = "Отпечаток (Fingerprint) для сверки личности и сессий", en = "Cryptographic fingerprint to verify identity and sessions", de = "Kryptografischer Fingerabdruck zur Identitätsprüfung", es = "Huella criptográfica para verificar identidad y sesiones", fr = "Empreinte cryptographique pour vérifier l'identité et les sessions", pt = "Impressão digital criptográfica para verificar identidade e sessões", tr = "Kimlik ve oturumları doğrulamak için kriptografik parmak izi"),
                                valueBadge = if (localFingerprint.isNotBlank()) localFingerprint.take(8) + "…" else null,
                                keywords = listOf("fingerprint", "key", "ключ", "отпечаток", "безопасность", "сверка", "mitm", "identity", "anahtar", "parmak izi", "güvenlik", "kimlik"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Безопасность", en = "Security", de = "Sicherheit", es = "Seguridad", fr = "Sécurité", pt = "Segurança", tr = "Güvenlik"),
                                categoryColor = Color(0xFF66BB6A),
                                title = Localizations.tr(appLanguage, ru = "Код-пароль приложения", en = "App Passcode Lock", de = "App-PIN-Sperre", es = "Bloqueo con código de la app", fr = "Verrouillage par code de l'application", pt = "Bloqueio por PIN do aplicativo", tr = "Uygulama PIN Kilidi"),
                                subtitle = Localizations.tr(appLanguage, ru = "4-значный PIN-код для защиты доступа к чатам", en = "4-digit PIN for securing app access", de = "4-stellige PIN zum Schutz des App-Zugriffs", es = "PIN de 4 dígitos para proteger el acceso a la app", fr = "Code PIN à 4 chiffres pour sécuriser l'accès à l'application", pt = "PIN de 4 dígitos para proteger o acesso ao app", tr = "Uygulama erişimini güvence altına almak için 4 haneli PIN"),
                                valueBadge = if (hasPasscode) Localizations.tr(appLanguage, ru = "Включен", en = "ON", de = "AN", es = "ACTIVADO", fr = "ACTIVÉ", pt = "ATIVADO", tr = "AÇIK") else Localizations.tr(appLanguage, ru = "Выключен", en = "OFF", de = "AUS", es = "DESACTIVADO", fr = "DÉSACTIVÉ", pt = "DESATIVADO", tr = "KAPALI"),
                                keywords = listOf("passcode", "pin", "lock", "пароль", "код", "код-пароль", "пин", "защита", "блокировка", "kilit", "şifre"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Безопасность", en = "Security", de = "Sicherheit", es = "Seguridad", fr = "Sécurité", pt = "Segurança", tr = "Güvenlik"),
                                categoryColor = Color(0xFF66BB6A),
                                title = Localizations.tr(appLanguage, ru = "Тревожный PIN-код (Duress)", en = "Duress Emergency PIN", de = "Duress-Notfall-PIN", es = "PIN de emergencia (Duress)", fr = "Code PIN d'urgence Duress", pt = "PIN de emergência Duress", tr = "Tehdit (Duress) Acil Durum PIN'i"),
                                subtitle = Localizations.tr(appLanguage, ru = "Экстренный код для стирания данных при принуждении", en = "Emergency PIN for forced data wipe", de = "Notfall-PIN zur erzwungenen Datenlöschung", es = "PIN de emergencia para borrado forzado de datos", fr = "Code PIN d'urgence pour l'effacement forcé des données", pt = "PIN de emergência para limpeza forçada de dados", tr = "Zorla veri silme için acil durum PIN'i"),
                                valueBadge = if (hasDuressPIN) Localizations.tr(appLanguage, ru = "Задан", en = "Set", de = "Gesetzt", es = "Configurado", fr = "Défini", pt = "Configurado", tr = "Ayarlandı") else Localizations.tr(appLanguage, ru = "Не задан", en = "Not set", de = "Nicht gesetzt", es = "No configurado", fr = "Non défini", pt = "Não configurado", tr = "Ayarlanmadı"),
                                keywords = listOf("duress", "emergency", "тревожный", "экстренный", "паника", "сброс", "tehdit", "acil", "sıfırlama"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Безопасность", en = "Security", de = "Sicherheit", es = "Seguridad", fr = "Sécurité", pt = "Segurança", tr = "Güvenlik"),
                                categoryColor = Color(0xFF66BB6A),
                                title = Localizations.tr(appLanguage, ru = "Разрешить скриншоты", en = "Allow Screenshots", de = "Screenshots erlauben", es = "Permitir capturas de pantalla", fr = "Autoriser les captures d'écran", pt = "Permitir capturas de tela", tr = "Ekran Görüntülerine İzin Ver"),
                                subtitle = Localizations.tr(appLanguage, ru = "Запрет создания снимков экрана и превью", en = "Block screen capture and task switcher preview", de = "Screenshot-Erstellung und Task-Vorschau blockieren", es = "Bloquear capturas de pantalla y vista previa de tareas", fr = "Bloquer les captures d'écran et l'aperçu multitâche", pt = "Bloquear capturas de tela e pré-visualização de tarefas", tr = "Ekran görüntüsü yakalamayı ve görev değiştirici önizlemesini engelle"),
                                valueBadge = if (allowScreenshots) "ON" else "OFF",
                                keywords = listOf("screenshot", "screenshots", "скриншот", "скриншоты", "снимок", "экран", "capture", "ekran görüntüsü"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Безопасность", en = "Security", de = "Sicherheit", es = "Seguridad", fr = "Sécurité", pt = "Segurança", tr = "Güvenlik"),
                                categoryColor = Color(0xFF66BB6A),
                                title = Localizations.tr(appLanguage, ru = "Клавиатура инкогнито", en = "Incognito Keyboard", de = "Inkognito-Tastatur", es = "Teclado incógnito", fr = "Clavier incognito", pt = "Teclado anônimo", tr = "Gizli Klavye"),
                                subtitle = Localizations.tr(appLanguage, ru = "Запрос на отключение обучения клавиатуры и сохранения текста", en = "Request keyboard to disable personalized learning and logging", de = "Tastatur auffordern, personalisiertes Lernen und Protokollieren zu deaktivieren", es = "Solicitar al teclado que desactive el aprendizaje personalizado y registro", fr = "Demander au clavier de désactiver l'apprentissage personnalisé et l'enregistrement", pt = "Solicitar ao teclado que desative o aprendizado personalizado e registro", tr = "Klavyeden kişiselleştirilmiş öğrenmeyi ve günlük kaydını devre dışı bırakmasını isteyin"),
                                valueBadge = if (incognitoKeyboard) "ON" else "OFF",
                                keywords = listOf("incognito", "keyboard", "инкогнито", "клавиатура", "gboard", "swiftkey", "ime", "t9", "gizli klavye"),
                                onClick = { activeSubPage = "security" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Сеть", en = "Network", de = "Netzwerk", es = "Red", fr = "Réseau", pt = "Rede", tr = "Ağ"),
                                categoryColor = Color(0xFF66BB6A),
                                title = Localizations.tr(appLanguage, ru = "Входящий порт Direct P2P", en = "Direct P2P Listening Port", de = "Direkter P2P-Listening-Port", es = "Puerto de escucha Direct P2P", fr = "Port d'écoute direct P2P", pt = "Porta de escuta direta P2P", tr = "Doğrudan P2P Dinleme Bağlantı Noktası"),
                                subtitle = Localizations.tr(appLanguage, ru = "Сетевой порт для принятия входящих P2P соединений", en = "Inbound network port for direct P2P connections", de = "Eingehender Netzwerkport für direkte P2P-Verbindungen", es = "Puerto de red entrante para conexiones P2P directas", fr = "Port réseau entrant pour les connexions directes P2P", pt = "Porta de rede de entrada para conexões P2P diretas", tr = "Doğrudan P2P bağlantıları için gelen ağ bağlantı noktası"),
                                valueBadge = "50001",
                                keywords = listOf("port", "p2p port", "direct p2p", "порт", "прямое соединение", "50001", "bağlantı noktası"),
                                onClick = { activeSubPage = "advanced_network" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Сеть", en = "Network", de = "Netzwerk", es = "Red", fr = "Réseau", pt = "Rede", tr = "Ağ"),
                                categoryColor = Color(0xFF29B6F6),
                                title = Localizations.tr(appLanguage, ru = "Трекеры и P2P обнаружение", en = "Trackers & Peer Discovery", de = "Tracker & Peer-Erkennung", es = "Rastreadores y descubrimiento de pares", fr = "Trackers et découverte de pairs", pt = "Rastreadores e descoberta de pares", tr = "İzleyiciler ve Eş Keşfi"),
                                subtitle = Localizations.tr(appLanguage, ru = "DHT, mDNS локальная сеть и список трекеров", en = "DHT, local mDNS discovery & tracker servers", de = "DHT, lokale mDNS-Erkennung & Tracker-Server", es = "DHT, descubrimiento mDNS local y servidores de rastreo", fr = "DHT, découverte mDNS locale et serveurs de trackers", pt = "DHT, descoberta mDNS local e servidores de rastreadores", tr = "DHT, yerel mDNS keşfi ve izleyici sunucuları"),
                                keywords = listOf("tracker", "trackers", "dht", "mdns", "announce", "трекер", "трекеры", "обнаружение", "izleyici", "keşif"),
                                onClick = { activeSubPage = "trackers" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Анонимизация", en = "Anonymization", de = "Anonymisierung", es = "Anonimización", fr = "Anonymisation", pt = "Anonimização", tr = "Anonimleştirme"),
                                categoryColor = Color(0xFFAB47BC),
                                title = Localizations.tr(appLanguage, ru = "Встроенный Tor & Мосты obfs4", en = "Embedded Tor & obfs4 Bridges", de = "Integrierter Tor & obfs4-Bridges", es = "Tor integrado y puentes obfs4", fr = "Tor intégré et ponts obfs4", pt = "Tor integrado e pontes obfs4", tr = "Gömülü Tor ve obfs4 Köprüleri"),
                                subtitle = Localizations.tr(appLanguage, ru = "Луковая маршрутизация, мосты obfs4 и ротация цепочки", en = "Onion routing, obfs4 bridges & circuit rotation", de = "Onion-Routing, obfs4-Bridges & Circuit-Rotation", es = "Enrutamiento Onion, puentes obfs4 y rotación de circuito", fr = "Routage en oignon, ponts obfs4 et rotation de circuit", pt = "Roteamento cebola, pontes obfs4 e rotação de circuito", tr = "Onion yönlendirme, obfs4 köprüleri ve devre rotasyonu"),
                                keywords = listOf("tor", "onion", "obfs4", "bridge", "bridges", "тор", "мосты", "анонимность", "цепочка", "circuit", "new identity", "ротация", "köprü", "anonimlik"),
                                onClick = { activeSubPage = "tor" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Сеть", en = "Network", de = "Netzwerk", es = "Red", fr = "Réseau", pt = "Rede", tr = "Ağ"),
                                categoryColor = Color(0xFF26A69A),
                                title = Localizations.tr(appLanguage, ru = "SOCKS5 / Внешний Прокси", en = "SOCKS5 / Custom Proxy", de = "SOCKS5 / Eigener Proxy", es = "SOCKS5 / Proxy personalizado", fr = "SOCKS5 / Proxy personnalisé", pt = "SOCKS5 / Proxy personalizado", tr = "SOCKS5 / Özel Vekil Sunucu"),
                                subtitle = Localizations.tr(appLanguage, ru = "Настройка внешнего SOCKS5 прокси или Orbot", en = "Route connections via custom SOCKS5 proxy or Orbot", de = "Verbindungen über eigenen SOCKS5-Proxy oder Orbot leiten", es = "Enrutar conexiones a través de un proxy SOCKS5 personalizado u Orbot", fr = "Acheminer les connexions via un proxy SOCKS5 personnalisé ou Orbot", pt = "Rotear conexões via proxy SOCKS5 personalizado ou Orbot", tr = "Bağlantıları özel SOCKS5 vekil sunucusu veya Orbot üzerinden yönlendirin"),
                                keywords = listOf("socks5", "proxy", "socks", "прокси", "хост", "порт", "1080", "9050", "orbot", "vekil sunucu"),
                                onClick = { activeSubPage = "trackers" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Оформление", en = "Appearance", de = "Erscheinungsbild", es = "Apariencia", fr = "Apparence", pt = "Aparência", tr = "Görünüm"),
                                categoryColor = Color(0xFFFFA726),
                                title = Localizations.tr(appLanguage, ru = "Сворачивать NexusTab по умолчанию", en = "Collapse NexusTab by Default", de = "NexusTab standardmäßig minimieren", es = "Colapsar NexusTab por defecto", fr = "Réduire NexusTab par défaut", pt = "Recolher NexusTab por padrão", tr = "NexusTab'ı Varsayılan Olarak Daralt"),
                                subtitle = Localizations.tr(appLanguage, ru = "Компактный режим виджета статуса на экране чатов", en = "Compact NexusTab status bar on chats tab", de = "Kompakte NexusTab-Statusleiste im Chats-Tab", es = "Barra de estado compacta de NexusTab en la pestaña de chats", fr = "Barre d'état compacte de NexusTab dans l'onglet des discussions", pt = "Barra de status compacta do NexusTab na aba de conversas", tr = "Sohbetler sekmesinde kompakt NexusTab durum çubuğu"),
                                keywords = listOf("nexustab", "nexus", "hero", "widget", "виджет", "сворачивать", "компактный", "статус", "daralt"),
                                onClick = { activeSubPage = "chat_settings" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Сеть", en = "Network", de = "Netzwerk", es = "Red", fr = "Réseau", pt = "Rede", tr = "Ağ"),
                                categoryColor = Color(0xFFAB47BC),
                                title = Localizations.tr(appLanguage, ru = "Настройки Yggdrasil", en = "Yggdrasil Settings", de = "Yggdrasil-Einstellungen", es = "Ajustes de Yggdrasil", fr = "Paramètres d'Yggdrasil", pt = "Configurações do Yggdrasil", tr = "Yggdrasil Ayarları"),
                                subtitle = Localizations.tr(appLanguage, ru = "Маршрутизация mesh-сети, пиры и генерация ключей", en = "Mesh routing, public & custom peers, key management", de = "Mesh-Routing, öffentliche & eigene Peers, Schlüsselverwaltung", es = "Enrutamiento mesh, pares públicos y personalizados, gestión de claves", fr = "Routage maillé, pairs publics et personnalisés, gestion des clés", pt = "Roteamento mesh, pares públicos e personalizados, gerenciamento de chaves", tr = "Mesh yönlendirme, genel ve özel eşler, anahtar yönetimi"),
                                keywords = listOf("yggdrasil", "peers", "mesh", "vpn", "nodes", "пиры", "узлы", "маршрутизация", "ключ", "ipv6", "иггдрасиль", "eşler"),
                                onClick = { activeSubPage = "yggdrasil_peers" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Уведомления", en = "Notifications", de = "Benachrichtigungen", es = "Notificaciones", fr = "Notifications", pt = "Notificações", tr = "Bildirimler"),
                                categoryColor = Color(0xFFEF5350),
                                title = Localizations.tr(appLanguage, ru = "Уведомления и звуки", en = "Notifications & Sounds", de = "Benachrichtigungen & Töne", es = "Notificaciones y sonidos", fr = "Notifications et sons", pt = "Notificações e sons", tr = "Bildirimler ve Sesler"),
                                subtitle = Localizations.tr(appLanguage, ru = "Звуковые сигналы, вибрация и превью сообщений", en = "Sounds, vibration & message previews", de = "Töne, Vibration & Nachrichtenvorschau", es = "Sonidos, vibración y vista previa de mensajes", fr = "Sons, vibration et aperçus des messages", pt = "Sons, vibração e pré-visualização de mensagens", tr = "Sesli uyarılar, titreşim ve mesaj önizlemeleri"),
                                keywords = listOf("notification", "notifications", "sound", "sounds", "vibration", "preview", "уведомление", "уведомления", "звуки", "превью", "bildirim", "sesler", "titreşim"),
                                onClick = { activeSubPage = "notifications" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Память", en = "Storage", de = "Speicher", es = "Almacenamiento", fr = "Stockage", pt = "Armazenamento", tr = "Depolama"),
                                categoryColor = Color(0xFF26A69A),
                                title = Localizations.tr(appLanguage, ru = "Данные и память (Кэш)", en = "Data & Storage (Cache)", de = "Daten & Speicher (Cache)", es = "Datos y almacenamiento (Caché)", fr = "Données et stockage (Cache)", pt = "Dados e armazenamento (Cache)", tr = "Veri ve Depolama (Önbellek)"),
                                subtitle = Localizations.tr(appLanguage, ru = "Использование памяти и очистка кэша файлов", en = "Storage usage & clearing media cache", de = "Speicherbelegung und Cache-Bereinigung", es = "Uso de almacenamiento y limpieza de caché de medios", fr = "Utilisation du stockage et nettoyage du cache des médias", pt = "Uso do armazenamento e limpeza do cache de mídia", tr = "Depolama kullanımı ve medya önbelleğini temizleme"),
                                keywords = listOf("data", "storage", "cache", "memory", "кэш", "память", "данные", "очистить", "медиа", "önbellek", "depolama"),
                                onClick = { activeSubPage = "storage" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Статистика", en = "Stats", de = "Statistiken", es = "Estadísticas", fr = "Statistiques", pt = "Estatísticas", tr = "İstatistikler"),
                                categoryColor = Color(0xFF42A5F5),
                                title = Localizations.tr(appLanguage, ru = "Использование сети (Трафик)", en = "Network Data Usage", de = "Netzwerk-Datennutzung", es = "Uso de datos de red", fr = "Consommation de données réseau", pt = "Uso de dados de rede", tr = "Ağ Veri Kullanımı"),
                                subtitle = Localizations.tr(appLanguage, ru = "Статистика входящего и исходящего P2P трафика", en = "Inbound and outbound P2P traffic statistics", de = "Statistiken zum eingehenden und ausgehenden P2P-Datenverkehr", es = "Estadísticas de tráfico P2P entrante y saliente", fr = "Statistiques du trafic P2P entrant et sortant", pt = "Estatísticas de tráfego P2P de entrada e saída", tr = "Gelen ve giden P2P veri trafiği istatistikleri"),
                                keywords = listOf("network usage", "traffic", "bytes", "трафик", "сеть", "байты", "статистика", "veri", "istatistik"),
                                onClick = { activeSubPage = "network_usage" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Система", en = "System", de = "System", es = "Sistema", fr = "Système", pt = "Sistema", tr = "Sistem"),
                                categoryColor = Color(0xFFEC407A),
                                title = Localizations.tr(appLanguage, ru = "Язык приложения", en = "App Language", de = "App-Sprache", es = "Idioma de la app", fr = "Langue de l'application", pt = "Idioma do aplicativo", tr = "Uygulama Dili"),
                                subtitle = Localizations.tr(appLanguage, ru = "Русский, English, Deutsch, Español, Türkçe...", en = "Russian, English, German, Spanish, Turkish...", de = "Russisch, Englisch, Deutsch, Spanisch, Türkisch...", es = "Ruso, Inglés, Alemán, Español, Turco...", fr = "Russe, Anglais, Allemand, Espagnol, Turc...", pt = "Russo, Inglês, Alemão, Espanhol, Turco...", tr = "Rusça, İngilizce, Almanca, İspanyolca, Türkçe..."),
                                valueBadge = appLanguage,
                                keywords = listOf("language", "ru", "en", "язык", "русский", "английский", "english", "dil", "türkçe"),
                                onClick = { activeSubPage = "language" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Справка", en = "Help", de = "Hilfe", es = "Ayuda", fr = "Aide", pt = "Ajuda", tr = "Yardım"),
                                categoryColor = Color(0xFF7E57C2),
                                title = Localizations.tr(appLanguage, ru = "Справка и руководства", en = "Help & Reference", de = "Hilfe & Referenz", es = "Ayuda y referencias", fr = "Aide et références", pt = "Ajuda e referências", tr = "Rehber ve Güvenlik İpuçları"),
                                subtitle = Localizations.tr(appLanguage, ru = "Ответы на частые вопросы и руководства P2P", en = "FAQ & P2P protocol reference guides", de = "FAQ & P2P-Protokoll-Leitfäden", es = "Preguntas frecuentes y guías del protocolo P2P", fr = "FAQ et guides de référence du protocole P2P", pt = "FAQ e guias de referência do protocolo P2P", tr = "Kavramlar, ağ rehberi ve gizlilik açıklamaları"),
                                keywords = listOf("help", "reference", "faq", "справка", "помощь", "вопросы", "руководство", "yardım", "rehber", "sss"),
                                onClick = { activeSubPage = "help_reference" }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Отладка", en = "Debug", de = "Debugging", es = "Depuración", fr = "Débogage", pt = "Depuração", tr = "Hata Ayıklama"),
                                categoryColor = Color(0xFF78909C),
                                title = Localizations.tr(appLanguage, ru = "Сетевой отладчик и Логи", en = "Network Diagnostics & Logs", de = "Netzwerkdiagnose & Protokolle", es = "Diagnóstico de red y registros", fr = "Diagnostics réseau et journaux", pt = "Diagnósticos de rede e registros", tr = "Ağ Tanılama ve Günlükler"),
                                subtitle = Localizations.tr(appLanguage, ru = "Мониторинг событий сети и соединений live", en = "Live monitoring of P2P network events", de = "Live-Überwachung von P2P-Netzwerkereignissen", es = "Monitoreo en vivo de eventos de red P2P", fr = "Surveillance en direct des événements réseau P2P", pt = "Monitoramento ao vivo de eventos da rede P2P", tr = "P2P ağ olaylarının canlı izlenmesi"),
                                keywords = listOf("log", "logs", "debugger", "diagnostics", "логи", "отладчик", "отладка", "günlük", "tanılama"),
                                onClick = { onShowLogs() }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Отладка", en = "Debug", de = "Debugging", es = "Depuración", fr = "Débogage", pt = "Depuração", tr = "Hata Ayıklama"),
                                categoryColor = Color(0xFF8D6E63),
                                title = Localizations.tr(appLanguage, ru = "Экспорт логов приложения", en = "Export App Logs", de = "App-Protokolle exportieren", es = "Exportar registros de la app", fr = "Exporter les journaux de l'application", pt = "Exportar registros do aplicativo", tr = "Uygulama Günlüklerini Dışa Aktar"),
                                subtitle = Localizations.tr(appLanguage, ru = "Поделиться файлом логов app.log", en = "Share app.log file", de = "app.log-Datei teilen", es = "Compartir archivo app.log", fr = "Partager le fichier app.log", pt = "Compartilhar arquivo app.log", tr = "app.log dosyasını paylaş"),
                                keywords = listOf("export", "share", "file", "экспорт", "поделиться", "лог-файл", "app.log", "dışa aktar", "paylaş"),
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
                                            val chooserTitle = Localizations.tr(appLanguage, ru = "Поделиться логами", en = "Share Logs", de = "Protokolle teilen", es = "Compartir registros", fr = "Partager les journaux", pt = "Compartilhar registros", tr = "Günlükleri Paylaş")
                                            context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error sharing logs: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val emptyMsg = Localizations.tr(appLanguage, ru = "Лог-файл пуст или еще не создан", en = "Log file is empty or not created yet", de = "Protokolldatei ist leer oder noch nicht erstellt", es = "El archivo de registro está vacío o aún no se ha creado", fr = "Le fichier journal est vide ou pas encore créé", pt = "O arquivo de log está vazio ou ainda não foi criado", tr = "Günlük dosyası boş veya henüz oluşturulmadı")
                                        Toast.makeText(context, emptyMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ),
                            DeepSettingItem(
                                category = Localizations.tr(appLanguage, ru = "Опасная зона", en = "Danger Zone", de = "Gefahrenzone", es = "Zona de peligro", fr = "Zone dangereuse", pt = "Zona de perigo", tr = "Tehlikeli Bölge"),
                                categoryColor = Color(0xFFFF5252),
                                title = Localizations.tr(appLanguage, ru = "Удалить аккаунт и данные", en = "Delete Account & Data", de = "Konto & Daten löschen", es = "Eliminar cuenta y datos", fr = "Supprimer le compte et les données", pt = "Excluir conta e dados", tr = "Hesabı ve Verileri Sil"),
                                subtitle = Localizations.tr(appLanguage, ru = "Полное удаление ключей и истории сообщений", en = "Permanently wipe identity keys and history", de = "Schlüssel und Nachrichtenverlauf dauerhaft löschen", es = "Eliminar permanentemente claves de identidad e historial", fr = "Effacer définitivement les clés d'identité et l'historique", pt = "Limpar permanentemente as chaves de identidade e histórico", tr = "Kimlik anahtarlarını ve mesaj geçmişini kalıcı olarak sil"),
                                keywords = listOf("delete", "wipe", "remove", "account", "удалить", "стереть", "аккаунт", "sil", "hesap"),
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
                                    Localizations.tr(
                                        appLanguage,
                                        ru = "НАЙДЕННЫЕ НАСТРОЙКИ (${matchingDeepResults.size})",
                                        en = "SEARCH RESULTS (${matchingDeepResults.size})",
                                        de = "SUCHERGEBNISSE (${matchingDeepResults.size})",
                                        es = "RESULTADOS DE BÚSQUEDA (${matchingDeepResults.size})",
                                        fr = "RÉSULTATS DE RECHERCHE (${matchingDeepResults.size})",
                                        pt = "RESULTADOS DA BUSCA (${matchingDeepResults.size})",
                                        tr = "ARAMA SONUÇLARI (${matchingDeepResults.size})"
                                    ),
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
                                            text = Localizations.tr(
                                                appLanguage,
                                                ru = "Ничего не найдено",
                                                en = "No settings found",
                                                de = "Keine Einstellungen gefunden",
                                                es = "No se encontraron ajustes",
                                                fr = "Aucun paramètre trouvé",
                                                pt = "Nenhuma configuração encontrada",
                                                tr = "Ayar bulunamadı"
                                            ),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = onSurfaceColor
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = Localizations.tr(
                                                appLanguage,
                                                ru = "Попробуйте изменить поисковый запрос",
                                                en = "Try changing your search query",
                                                de = "Versuchen Sie eine andere Suchanfrage",
                                                es = "Intenta cambiar tu búsqueda",
                                                fr = "Essayez de modifier votre recherche",
                                                pt = "Tente alterar sua busca",
                                                tr = "Arama sorgunuzu değiştirmeyi deneyin"
                                            ),
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
                                                val addrCopiedMsg = Localizations.tr(appLanguage, ru = "Адрес контакта скопирован", en = "Contact address copied", de = "Kontaktadresse kopiert", es = "Dirección de contacto copiada", fr = "Adresse du contact copiée", pt = "Endereço de contato copiado", tr = "Kişi adresi kopyalandı")
                                                Toast.makeText(
                                                    context,
                                                    addrCopiedMsg,
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
                                                Localizations.tr(appLanguage, ru = "О себе: Нажмите, чтобы добавить...", en = "About me: Tap to add...", de = "Über mich: Zum Hinzufügen tippen...", es = "Sobre mí: Toca para agregar...", fr = "À propos de moi : Appuyez pour ajouter...", pt = "Sobre mim: Toque para adicionar...", tr = "Hakkımda: Eklemek için dokunun...")
                                            } else {
                                                Localizations.tr(appLanguage, ru = "О себе: $aboutMeText", en = "About me: $aboutMeText", de = "Über mich: $aboutMeText", es = "Sobre mí: $aboutMeText", fr = "À propos de moi : $aboutMeText", pt = "Sobre mim: $aboutMeText", tr = "Hakkımda: $aboutMeText")
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
                            Localizations.tr(appLanguage, "🛡 АНОНИМНОСТЬ И TOR", "🛡 ANONYMITY & TOR", "🛡 ANONYMITÄT & TOR", "🛡 ANONIMATO Y TOR", "🛡 ANONYMAT & TOR", "🛡 ANONIMATO & TOR", tr = "🛡 ANONİMLİK VE TOR"),
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
                                    title = Localizations.tr(
                                        appLanguage,
                                        ru = "Tor и скрытые сервисы (.onion)",
                                        en = "Tor & Hidden Services (.onion)",
                                        de = "Tor & Versteckte Dienste (.onion)",
                                        es = "Tor y servicios ocultos (.onion)",
                                        fr = "Tor et services cachés (.onion)",
                                        pt = "Tor e serviços ocultos (.onion)",
                                        tr = "Tor ve Gizli Servisler (.onion)"
                                    ),
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Встроенный демон, мосты obfs4, смена личности",
                                        en = "Embedded daemon, obfs4 bridges, circuit renewal",
                                        de = "Integrierter Daemon, obfs4-Bridges, Identitätswechsel",
                                        es = "Demonio integrado, puentes obfs4, renovación de circuito",
                                        fr = "Démon intégré, ponts obfs4, renouvellement du circuit",
                                        pt = "Daemon integrado, pontes obfs4, renovação de circuito",
                                        tr = "Gömülü daemon, obfs4 köprüleri, devre yenileme"
                                    ),
                                    value = if (isTorRunning) {
                                        Localizations.tr(appLanguage, ru = "● Активен", en = "● Active", de = "● Aktiv", es = "● Activo", fr = "● Actif", pt = "● Ativo", tr = "● Aktif")
                                    } else {
                                        Localizations.tr(appLanguage, ru = "○ Ожидание", en = "○ Standby", de = "○ Standby", es = "○ Espera", fr = "○ En attente", pt = "○ Em espera", tr = "○ Beklemede")
                                    },
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
                            Localizations.tr(appLanguage, "🔒 БЕЗОПАСНОСТЬ", "🔒 SECURITY", "🔒 SICHERHEIT", "🔒 SEGURIDAD", "🔒 SÉCURITÉ", "🔒 SEGURANÇA", tr = "🔒 GÜVENLİK"),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Личный ключ, отпечаток (Fingerprint) и сверка",
                                        en = "Personal key, fingerprint and identity verification",
                                        de = "Persönlicher Schlüssel, Fingerabdruck & Identitätsprüfung",
                                        es = "Clave personal, huella digital y verificación de identidad",
                                        fr = "Clé personnelle, empreinte et vérification d'identité",
                                        pt = "Chave pessoal, impressão digital e verificação de identidade",
                                        tr = "Kişisel anahtar, parmak izi ve kimlik doğrulama"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "4-значный PIN-код и тревожный пароль (Duress)",
                                        en = "4-digit PIN lock and Duress emergency wipe",
                                        de = "4-stellige PIN-Sperre & Duress-Notfalllöschung",
                                        es = "Bloqueo con PIN de 4 dígitos y borrado de emergencia (Duress)",
                                        fr = "Verrouillage par code PIN à 4 chiffres et effacement d'urgence Duress",
                                        pt = "Bloqueio por PIN de 4 dígitos e limpeza de emergência Duress",
                                        tr = "4 haneli PIN kilidi ve Tehdit (Duress) acil durum sıfırlama"
                                    ),
                                    value = if (hasPasscode) Localizations.tr(appLanguage, ru = "Включен", en = "ON", de = "AN", es = "ACTIVADO", fr = "ACTIVÉ", pt = "ATIVADO", tr = "AÇIK") else null,
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Блокировка скриншотов и клавиатура инкогнито",
                                        en = "Screen capture blocking & incognito keyboard",
                                        de = "Screenshot-Sperre & Inkognito-Tastatur",
                                        es = "Bloqueo de captura de pantalla y teclado incógnito",
                                        fr = "Blocage des captures d'écran et clavier incognito",
                                        pt = "Bloqueio de captura de tela e teclado anônimo",
                                        tr = "Ekran görüntüsü engelleme ve gizli klavye"
                                    ),
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
                            Localizations.tr(appLanguage, "🌐 СЕТЬ И P2P", "🌐 NETWORK & P2P", "🌐 NETZWERK & P2P", "🌐 RED Y P2P", "🌐 RÉSEAU & P2P", "🌐 REDE E P2P", tr = "🌐 AĞ VE P2P"),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "IPv6 mesh-сеть, публичные и пользовательские пиры",
                                        en = "IPv6 mesh network, public and custom peers",
                                        de = "IPv6-Mesh-Netzwerk, öffentliche und eigene Peers",
                                        es = "Red mesh IPv6, pares públicos y personalizados",
                                        fr = "Réseau maillé IPv6, pairs publics et personnalisés",
                                        pt = "Rede mesh IPv6, pares públicos e personalizados",
                                        tr = "IPv6 mesh ağı, genel ve özel eşler"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "DHT-обнаружение, UDP/HTTP трекеры и SOCKS5",
                                        en = "DHT discovery, UDP/HTTP trackers and SOCKS5",
                                        de = "DHT-Erkennung, UDP/HTTP-Tracker und SOCKS5",
                                        es = "Descubrimiento DHT, rastreadores UDP/HTTP y SOCKS5",
                                        fr = "Découverte DHT, trackers UDP/HTTP et SOCKS5",
                                        pt = "Descoberta DHT, rastreadores UDP/HTTP e SOCKS5",
                                        tr = "DHT keşfi, UDP/HTTP izleyicileri ve SOCKS5"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Порт P2P, Wi-Fi discovery, IPv4 и UPnP",
                                        en = "P2P port, Wi-Fi discovery, IPv4 and UPnP NAT",
                                        de = "P2P-Port, Wi-Fi-Erkennung, IPv4 und UPnP NAT",
                                        es = "Puerto P2P, descubrimiento Wi-Fi, IPv4 y UPnP NAT",
                                        fr = "Port P2P, découverte Wi-Fi, IPv4 et UPnP NAT",
                                        pt = "Porta P2P, descoberta Wi-Fi, IPv4 e UPnP NAT",
                                        tr = "P2P bağlantı noktası, Wi-Fi keşfi, IPv4 ve UPnP NAT"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Статистика входящего и исходящего P2P трафика",
                                        en = "Inbound and outbound P2P traffic statistics",
                                        de = "Statistiken zum eingehenden und ausgehenden P2P-Datenverkehr",
                                        es = "Estadísticas de tráfico P2P entrante y saliente",
                                        fr = "Statistiques du trafic P2P entrant et sortant",
                                        pt = "Estatísticas de tráfego P2P de entrada e saída",
                                        tr = "Gelen ve giden P2P veri trafiği istatistikleri"
                                    ),
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
                            Localizations.tr(appLanguage, "🎨 ЧАТЫ И ОФОРМЛЕНИЕ", "🎨 CHATS & APPEARANCE", "🎨 CHATS & DESIGN", "🎨 CHATS Y APARIENCIA", "🎨 DISCUSSIONS & THÈME", "🎨 CONVERSAS E APARÊNCIA", tr = "🎨 SOHBETLER VE GÖRÜNÜM"),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Цветовая схема, светлая тема, AMOLED и иконка",
                                        en = "Color accent, light mode, AMOLED and launcher icon",
                                        de = "Farbackzent, heller Modus, AMOLED und App-Symbol",
                                        es = "Acento de color, modo claro, AMOLED e icono de inicio",
                                        fr = "Accent de couleur, mode clair, AMOLED et icône d'application",
                                        pt = "Acento de cor, modo claro, AMOLED e ícone do app",
                                        tr = "Renk vurgusu, açık tema, AMOLED ve başlatıcı simgesi"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Создание наклеек, Telegram .tgs и управление паками",
                                        en = "Custom stickers, Telegram .tgs packs & editor",
                                        de = "Eigene Sticker, Telegram-.tgs-Pakete und Editor",
                                        es = "Stickers personalizados, paquetes .tgs de Telegram y editor",
                                        fr = "Autocollants personnalisés, packs .tgs Telegram et éditeur",
                                        pt = "Figurinhas personalizadas, pacotes .tgs do Telegram e editor",
                                        tr = "Özel çıkartmalar, Telegram .tgs paketleri ve düzenleyici"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Звуковые сигналы, вибрация и превью сообщений",
                                        en = "Push alerts, vibration and message previews",
                                        de = "Benachrichtigungstöne, Vibration und Nachrichtenvorschau",
                                        es = "Alertas sonoras, vibración y vista previa de mensajes",
                                        fr = "Alertes sonores, vibration et aperçus des messages",
                                        pt = "Alertas sonoros, vibração e pré-visualização de mensagens",
                                        tr = "Sesli uyarılar, titreşim ve mesaj önizlemeleri"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Использование памяти и очистка кэша файлов",
                                        en = "Storage breakdown & cache cleanup manager",
                                        de = "Speicherbelegung und Cache-Bereinigung",
                                        es = "Desglose de almacenamiento y limpieza de caché",
                                        fr = "Utilisation du stockage et gestionnaire de nettoyage du cache",
                                        pt = "Uso do armazenamento e gerenciador de limpeza de cache",
                                        tr = "Depolama kullanımı ve önbellek temizleme yöneticisi"
                                    ),
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
                            Localizations.tr(appLanguage, "⚙️ ПРИЛОЖЕНИЕ", "⚙️ APPLICATION", "⚙️ ANWENDUNG", "⚙️ APLICACIÓN", "⚙️ APPLICATION", "⚙️ APLICATIVO", tr = "⚙️ UYGULAMA"),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Русский, English, Deutsch, Español...",
                                        en = "Russian, English, German, Spanish...",
                                        de = "Russisch, Englisch, Deutsch, Spanisch...",
                                        es = "Ruso, Inglés, Alemán, Español...",
                                        fr = "Russe, Anglais, Allemand, Espagnol...",
                                        pt = "Russo, Inglês, Alemão, Espanhol...",
                                        tr = "Rusça, İngilizce, Almanca, İspanyolca..."
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Мониторинг событий сети и соединений live",
                                        en = "Live monitoring of P2P network events",
                                        de = "Live-Überwachung von P2P-Netzwerkereignissen",
                                        es = "Monitoreo en vivo de eventos de red P2P",
                                        fr = "Surveillance en direct des événements réseau P2P",
                                        pt = "Monitoramento ao vivo de eventos da rede P2P",
                                        tr = "P2P ağ olaylarının canlı izlenmesi"
                                    ),
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
                                    subtitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Поделиться файлом логов app.log",
                                        en = "Share app.log file",
                                        de = "app.log-Datei teilen",
                                        es = "Compartir archivo app.log",
                                        fr = "Partager le fichier app.log",
                                        pt = "Compartilhar arquivo app.log",
                                        tr = "app.log dosyasını paylaş"
                                    ),
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
                                                val shareChooserTitle = Localizations.tr(appLanguage, ru = "Поделиться логами", en = "Share Logs", de = "Protokolle teilen", es = "Compartir registros", fr = "Partager les journaux", pt = "Compartilhar registros", tr = "Günlükleri Paylaş")
                                                context.startActivity(Intent.createChooser(shareIntent, shareChooserTitle))
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error sharing logs: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            val emptyLogMsg = Localizations.tr(appLanguage, ru = "Лог-файл пуст или еще не создан", en = "Log file is empty or not created yet", de = "Protokolldatei ist leer oder noch nicht erstellt", es = "El archivo de registro está vacío o aún no se ha creado", fr = "Le fichier journal est vide ou pas encore créé", pt = "O arquivo de log está vazio ou ainda não foi criado", tr = "Günlük dosyası boş veya henüz oluşturulmadı")
                                            Toast.makeText(context, emptyLogMsg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f))

                                val currentVerName = remember { AppUpdateManager.getCurrentVersionName(context) }
                                val hasUpdate = availableUpdateRelease != null
                                val updateTitle = Localizations.tr(
                                        appLanguage,
                                        ru = "Проверить обновления",
                                        en = "Check for Updates",
                                        de = "Nach Updates suchen",
                                        es = "Buscar actualizaciones",
                                        fr = "Rechercher des mises à jour",
                                        pt = "Verificar atualizações",
                                        tr = "Güncellemeleri Kontrol Et"
                                    )
                                    val updateSubtitle = if (isCheckingUpdate) {
                                        Localizations.tr(
                                            appLanguage,
                                            ru = "Проверка релизов на GitHub...",
                                            en = "Checking GitHub releases...",
                                            de = "Prüfe GitHub-Releases...",
                                            es = "Comprobando versiones en GitHub...",
                                            fr = "Vérification des versions GitHub...",
                                            pt = "Verificando versões no GitHub...",
                                            tr = "GitHub sürümleri kontrol ediliyor..."
                                        )
                                    } else if (hasUpdate) {
                                        val newVer = availableUpdateRelease?.versionName ?: ""
                                        Localizations.tr(
                                            appLanguage,
                                            ru = "Доступна новая версия v$newVer • Нажмите для установки",
                                            en = "New version v$newVer available • Tap to install",
                                            de = "Neue Version v$newVer verfügbar • Zum Installieren tippen",
                                            es = "Nueva versión v$newVer disponible • Toca para instalar",
                                            fr = "Nouvelle version v$newVer disponible • Appuyer pour installer",
                                            pt = "Nova versão v$newVer disponível • Toque para instalar",
                                            tr = "Yeni sürüm v$newVer mevcut • Yüklemek için dokunun"
                                        )
                                    } else {
                                        Localizations.tr(
                                            appLanguage,
                                            ru = "Текущая версия: v$currentVerName",
                                            en = "Current version: v$currentVerName",
                                            de = "Aktuelle Version: v$currentVerName",
                                            es = "Versión actual: v$currentVerName",
                                            fr = "Version actuelle : v$currentVerName",
                                            pt = "Versão atual: v$currentVerName",
                                            tr = "Geçerli sürüm: v$currentVerName"
                                        )
                                    }
                                SettingsRow(
                                    title = updateTitle,
                                    subtitle = updateSubtitle,
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
                                subtitle = Localizations.tr(
                                    appLanguage,
                                    ru = "Полное удаление ключей и истории сообщений",
                                    en = "Permanently wipe identity keys and history",
                                    de = "Schlüssel und Nachrichtenverlauf dauerhaft löschen",
                                    es = "Eliminar permanentemente claves de identidad e historial",
                                    fr = "Effacer définitivement les clés d'identité et l'historique",
                                    pt = "Limpar permanentemente as chaves de identidade e histórico",
                                    tr = "Kimlik anahtarlarını ve mesaj geçmişini kalıcı olarak sil"
                                ),
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
                            text = "2PChat v$footerVerName • Go Core v1.6 (P2P)",
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
            val photoRemovedMsg = Localizations.tr(appLanguage, ru = "Фото профиля удалено", en = "Profile photo removed", de = "Profilbild entfernt", es = "Foto de perfil eliminada", fr = "Photo de profil supprimée", pt = "Foto de perfil removida", tr = "Profil fotoğrafı kaldırıldı")
            Toast.makeText(context, photoRemovedMsg, Toast.LENGTH_SHORT).show()
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
                        is UpdateCheckResult.UpdateAvailable -> Localizations.tr(appLanguage, ru = "Доступно обновление", en = "Update Available", de = "Update verfügbar", es = "Actualización disponible", fr = "Mise à jour disponible", pt = "Atualização disponível", tr = "Güncelleme Mevcut")
                        is UpdateCheckResult.UpToDate -> Localizations.tr(appLanguage, ru = "У вас последняя версия", en = "Up to date", de = "Auf dem neuesten Stand", es = "Actualizado", fr = "À jour", pt = "Atualizado", tr = "Güncel")
                        is UpdateCheckResult.Error -> Localizations.tr(appLanguage, ru = "Ошибка проверки", en = "Check Failed", de = "Prüfung fehlgeschlagen", es = "Error de comprobación", fr = "Échec de la vérification", pt = "Falha na verificação", tr = "Kontrol Başarısız")
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
                            val newVerPrefix = Localizations.tr(appLanguage, ru = "Новая версия", en = "New version", de = "Neue Version", es = "Nueva versión", fr = "Nouvelle version", pt = "Nova versão", tr = "Yeni sürüm")
                            val curVerPrefix = Localizations.tr(appLanguage, ru = "Текущая версия", en = "Current version", de = "Aktuelle Version", es = "Versión actual", fr = "Version actuelle", pt = "Versão atual", tr = "Geçerli sürüm")
                            Text(
                                text = "$newVerPrefix: v${rel.versionName}$sizeMb\n$curVerPrefix: v${res.currentVersion}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = primaryColor
                            )
                            if (rel.changelog.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = Localizations.tr(appLanguage, ru = "Что нового:", en = "What's new:", de = "Was gibt's Neues:", es = "Novedades:", fr = "Nouveautés :", pt = "O que há de novo:", tr = "Yenilikler:"),
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
                                text = Localizations.tr(
                                    appLanguage,
                                    ru = "Установлена актуальная версия v${res.currentVersion}. Обновлений не найдено.",
                                    en = "Installed version v${res.currentVersion} is up to date.",
                                    de = "Sie haben die neueste Version v${res.currentVersion}. Keine Updates gefunden.",
                                    es = "Tienes la última versión v${res.currentVersion}. No se encontraron actualizaciones.",
                                    fr = "Vous disposez de la dernière version v${res.currentVersion}. Aucune mise à jour trouvée.",
                                    pt = "Você possui a versão mais recente v${res.currentVersion}. Nenhuma atualização encontrada.",
                                    tr = "En son v${res.currentVersion} sürümüne sahipsiniz. Güncelleme bulunamadı."
                                ),
                                fontSize = 14.sp,
                                color = onSurfaceVariant
                            )
                        }
                        is UpdateCheckResult.Error -> {
                            val failPrefix = Localizations.tr(appLanguage, ru = "Не удалось проверить обновления", en = "Failed to check for updates", de = "Updates konnten nicht geprüft werden", es = "No se pudieron buscar actualizaciones", fr = "Impossible de vérifier les mises à jour", pt = "Falha ao verificar atualizações", tr = "Güncellemeler kontrol edilemedi")
                            Text(
                                text = "$failPrefix:\n${res.message}",
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
                                            Localizations.tr(appLanguage, ru = "Разрешите установку неизвестных приложений", en = "Please allow installing unknown apps", de = "Bitte erlauben Sie die Installation unbekannter Apps", es = "Permita la instalación de aplicaciones desconocidas", fr = "Veuillez autoriser l'installation d'applications inconnues", pt = "Permita a instalação de aplicativos desconhecidos", tr = "Lütfen bilinmeyen uygulamaların yüklenmesine izin verin"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        AppUpdateManager.openInstallPermissionSettings(context)
                                    } else {
                                        isDownloadingApk = true
                                        downloadProgress = 0f
                                        downloadStatusText = Localizations.tr(appLanguage, ru = "Подготовка загрузки...", en = "Preparing download...", de = "Download wird vorbereitet...", es = "Preparando descarga...", fr = "Préparation du téléchargement...", pt = "Preparando download...", tr = "İndirme hazırlanıyor...")
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
                                text = if (isDownloadingApk) {
                                    Localizations.tr(appLanguage, ru = "Загрузка...", en = "Downloading...", de = "Wird heruntergeladen...", es = "Descargando...", fr = "Téléchargement...", pt = "Baixando...", tr = "İndiriliyor...")
                                } else {
                                    Localizations.tr(appLanguage, ru = "Скачать и установить", en = "Download & Install", de = "Herunterladen & Installieren", es = "Descargar e instalar", fr = "Télécharger et installer", pt = "Baixar e instalar", tr = "İndir ve Yükle")
                                },
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
                                text = Localizations.tr(appLanguage, ru = "Понятно", en = "OK", de = "OK", es = "Entendido", fr = "Compris", pt = "Entendido", tr = "Tamam"),
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
                            text = Localizations.tr(appLanguage, ru = "Позже", en = "Later", de = "Später", es = "Más tarde", fr = "Plus tard", pt = "Mais tarde", tr = "Daha Sonra"),
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
                    contentDescription = Localizations.tr(appLanguage, "Назад", "Back", "Zurück", "Atrás", "Retour", "Voltar", tr = "Geri"),
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
