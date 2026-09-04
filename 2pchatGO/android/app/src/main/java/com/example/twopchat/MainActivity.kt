package com.example.twopchat

import com.example.twopchat.logging.SafeLog

import android.os.Bundle
import com.example.twopchat.config.*
import com.example.twopchat.relay.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.tor.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.twopchat.theme._2PChatTheme

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.InternalComposeUiApi
import com.example.twopchat.theme.StealthBlack
import com.example.twopchat.data.Localizations
import com.example.twopchat.ui.disguise.CurrencyRatesScreen

@OptIn(InternalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    private var lastInteractionTime = System.currentTimeMillis()
    private var pauseTime = 0L
    private val isAppLockedState = mutableStateOf(false)
    private val isStealthDisguiseLockedState = mutableStateOf(false)
    private val reduceMotionState = mutableStateOf(false)
    private val appMotionDurationScale = AppMotionDurationScale()
    private lateinit var appPreferences: SharedPreferences
    private val motionPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == REDUCE_MOTION_SETTING) {
                val reduceMotion = preferences.getBoolean(REDUCE_MOTION_SETTING, false)
                reduceMotionState.value = reduceMotion
                appMotionDurationScale.animationsEnabled = !reduceMotion
            }
        }
    private val systemAnimationScaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateSystemAnimationScale()
        }
    }

    private fun updateSystemAnimationScale() {
        appMotionDurationScale.systemScaleFactor = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }

    private fun applyScreenSecurity() {
        val sharedPrefsTemp = P2PPreferences.prefs(this)
        val blockScreenshots = sharedPrefsTemp.getBoolean("settings_screenshots", false)
        if (blockScreenshots) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun checkAutoLockOnResume() {
        val prefs = P2PPreferences.prefs(this)
        if (prefs.getBoolean("settings_stealth_disguise", false)) {
            isStealthDisguiseLockedState.value = true
        }

        val hasPasscodeConfigured = prefs.getBoolean("settings_passcode", false) &&
            !prefs.getString("passcode_value", null).isNullOrEmpty()

        if (hasPasscodeConfigured && !isAppLockedState.value) {
            val timeoutMinutes = prefs.getInt("passcode_autolock_minutes", 1)
            val now = System.currentTimeMillis()
            val elapsedSincePause = if (pauseTime > 0L) now - pauseTime else 0L
            val elapsedSinceInteraction = now - lastInteractionTime
            val effectiveElapsed = maxOf(elapsedSincePause, elapsedSinceInteraction)

            if (effectiveElapsed >= timeoutMinutes * 60 * 1000L) {
                isAppLockedState.value = true
                P2PPreferences.setAppLocked(true)
                SecureStorage.clearDbPassphrase()
                com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
            }
        }
        pauseTime = 0L
    }

    override fun onResume() {
        super.onResume()
        checkAutoLockOnResume()
        applyScreenSecurity()
        val appContext = applicationContext
        val preferences = P2PPreferences.prefs(appContext)
        val hasLocalIdentity =
            preferences.getBoolean("onboarding_completed", false) &&
                !preferences.getString("username_profile", null).isNullOrBlank()
        if (hasLocalIdentity) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, P2PRelayService::class.java),
                )
                P2PMessageRelay.triggerImmediateReconnect(appContext)
            }.onFailure {
                SafeLog.w("MainActivity", "Could not start P2PRelayService on resume", it)
            }
        }
        if (
            preferences.getBoolean("settings_yggdrasil", false) &&
            com.example.twopchat.yggdrasil.PacketTunnelProvider.isTunnelActive(appContext) &&
            android.net.VpnService.prepare(appContext) == null
        ) {
            runCatching {
                startService(
                    Intent(
                        appContext,
                        com.example.twopchat.yggdrasil.PacketTunnelProvider::class.java,
                    ).apply {
                        action =
                            com.example.twopchat.yggdrasil.PacketTunnelProvider.ACTION_CONNECT
                    },
                )
            }.onFailure {
                SafeLog.w("MainActivity", "Could not heal Yggdrasil on resume", it)
            }
        }
        com.example.twopchat.security.TemporaryCacheSanitizer.sanitizeTempCache(appContext)
    }

    override fun onPause() {
        super.onPause()
        pauseTime = System.currentTimeMillis()
    }

    override fun onStop() {
        super.onStop()
        if (pauseTime == 0L) {
            pauseTime = System.currentTimeMillis()
        }
        com.example.twopchat.security.TemporaryCacheSanitizer.sanitizeTempCache(applicationContext)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!isAppLockedState.value) {
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenSecurity()

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Initialize Native Go Core, Keystore, and Database asynchronously to avoid UI main thread ANR/lag
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NativeBridge.initialize()
                SecureStorage.prewarm(appContext)
                com.example.twopchat.data.ChatDatabaseHelper.getInstance(appContext).warmup()
                if (P2PPreferences.isTorEnabled(appContext)) {
                    TorManager.startTor(appContext)
                }
                val relayPrefs = P2PPreferences.prefs(appContext)
                val hasLocalIdentity =
                    relayPrefs.getBoolean("onboarding_completed", false) &&
                        !relayPrefs.getString("username_profile", null).isNullOrBlank()
                if (hasLocalIdentity) {
                    androidx.core.content.ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, P2PRelayService::class.java),
                    )
                }
            } catch (e: Exception) {
                SafeLog.e("MainActivity", "Error initializing Go native core or database in background", e)
            }
        }

        // Start Yggdrasil service automatically if enabled
        val yggPrefs = P2PPreferences.prefs(this)
        if (yggPrefs.getBoolean("settings_yggdrasil", false)) {
            val mode = P2PPreferences.getYggdrasilMode(applicationContext)
            if (mode == P2PPreferences.YggdrasilMode.PROXY || android.net.VpnService.prepare(applicationContext) == null) {
                com.example.twopchat.yggdrasil.YggdrasilCoordinator.start(applicationContext)
            }
        }

        val sharedPrefs = P2PPreferences.prefs(this)
        appPreferences = sharedPrefs

        val hasPasscodeConfiguredOnStart = sharedPrefs.getBoolean("settings_passcode", false) &&
            !sharedPrefs.getString("passcode_value", null).isNullOrEmpty()
        if (hasPasscodeConfiguredOnStart) {
            isAppLockedState.value = true
            P2PPreferences.setAppLocked(true)
            SecureStorage.clearDbPassphrase()
            com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
        }

        if (sharedPrefs.getBoolean("settings_stealth_disguise", false)) {
            isStealthDisguiseLockedState.value = true
        }

        reduceMotionState.value = sharedPrefs.getBoolean(REDUCE_MOTION_SETTING, false)
        appMotionDurationScale.animationsEnabled = !reduceMotionState.value
        updateSystemAnimationScale()
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            systemAnimationScaleObserver,
        )
        sharedPrefs.registerOnSharedPreferenceChangeListener(motionPreferenceListener)
        val motionDurationScale = appMotionDurationScale
        WindowRecomposerPolicy.setFactory(
            WindowRecomposerFactory { view ->
                view.createLifecycleAwareWindowRecomposer(
                    coroutineContext = motionDurationScale,
                )
            }
        )

        enableEdgeToEdge()
        
        setContent {
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1000) // 1 second display
                showSplash = false
            }

            var isDarkTheme by remember { mutableStateOf(sharedPrefs.getString("theme_mode", "dark") == "dark") }
            var accentScheme by remember {
                val saved = sharedPrefs.getString("accent_scheme", null)
                val legacyCerulean = sharedPrefs.getBoolean("use_cerulean", false)
                mutableStateOf(saved ?: if (legacyCerulean) "cerulean" else "mint")
            }
            var useCerulean by remember(accentScheme) { mutableStateOf(accentScheme == "cerulean") }
            var useAmoled by remember { mutableStateOf(sharedPrefs.getBoolean("use_amoled", false)) }
            var appLanguage by remember { mutableStateOf(P2PPreferences.getAppLanguage(this@MainActivity)) }

            LaunchedEffect(isDarkTheme, useAmoled) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        androidx.activity.SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        androidx.activity.SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }
            
            var isAppLocked by isAppLockedState
            var isStealthDisguiseLocked by isStealthDisguiseLockedState
            val hasPasscodeConfigured = remember(isAppLocked) {
                sharedPrefs.getBoolean("settings_passcode", false) &&
                    !sharedPrefs.getString("passcode_value", null).isNullOrEmpty()
            }

            // Continuous foreground inactivity auto-lock check
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1000L) // check every 1 second
                    val passcodeActive = sharedPrefs.getBoolean("settings_passcode", false) &&
                        !sharedPrefs.getString("passcode_value", null).isNullOrEmpty()
                    if (passcodeActive && !isAppLockedState.value) {
                        val timeoutMinutes = sharedPrefs.getInt("passcode_autolock_minutes", 1)
                        val elapsed = System.currentTimeMillis() - lastInteractionTime
                        if (elapsed >= timeoutMinutes * 60 * 1000L) {
                            isAppLockedState.value = true
                            P2PPreferences.setAppLocked(true)
                            SecureStorage.clearDbPassphrase()
                            com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
                        }
                    }
                }
            }

            LaunchedEffect(isAppLocked) {
                P2PPreferences.setAppLocked(isAppLocked)
                if (isAppLocked) {
                    SecureStorage.clearDbPassphrase()
                    com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
                }
            }

            var incognitoKeyboardEnabled by remember {
                mutableStateOf(P2PPreferences.isIncognitoKeyboardEnabled(this@MainActivity))
            }

            val prefsListener = remember(sharedPrefs) {
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        P2PPreferences.INCOGNITO_KEYBOARD, null -> {
                            incognitoKeyboardEnabled = P2PPreferences.isIncognitoKeyboardEnabled(this@MainActivity)
                        }
                        "theme_mode" -> {
                            isDarkTheme = sharedPrefs.getString("theme_mode", "dark") == "dark"
                        }
                        "accent_scheme", "use_cerulean" -> {
                            val saved = sharedPrefs.getString("accent_scheme", null)
                            val legacyCerulean = sharedPrefs.getBoolean("use_cerulean", false)
                            accentScheme = saved ?: if (legacyCerulean) "cerulean" else "mint"
                            useCerulean = accentScheme == "cerulean"
                        }
                        "use_amoled" -> {
                            useAmoled = sharedPrefs.getBoolean("use_amoled", false)
                        }
                        "settings_language", "app_language" -> {
                            appLanguage = P2PPreferences.getAppLanguage(this@MainActivity)
                        }
                    }
                }
            }

            DisposableEffect(sharedPrefs, prefsListener) {
                sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
                onDispose {
                    sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
                }
            }

            _2PChatTheme(
                darkTheme = isDarkTheme,
                accentScheme = accentScheme,
                useCerulean = accentScheme == "cerulean",
                useAmoled = useAmoled,
                animationsEnabled = !reduceMotionState.value,
            ) {
                com.example.twopchat.ui.util.P2PKeyboardOptions.IncognitoKeyboardScope(
                    isIncognito = incognitoKeyboardEnabled
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) { 
                        val currentScreen = when {
                        showSplash -> "splash"
                        isStealthDisguiseLocked && sharedPrefs.getBoolean("settings_stealth_disguise", false) -> "disguise"
                        isAppLocked && hasPasscodeConfigured -> "unlock"
                        else -> "main"
                    }
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(com.example.twopchat.theme.MotionTokens.DurationNormalMs, easing = com.example.twopchat.theme.MotionTokens.EmphasizedEasing)) togetherWith
                                fadeOut(animationSpec = tween(com.example.twopchat.theme.MotionTokens.DurationNormalMs, easing = com.example.twopchat.theme.MotionTokens.EmphasizedEasing))
                        },
                        label = "screen_transition",
                        modifier = Modifier.fillMaxSize()
                    ) { targetScreen ->
                        when (targetScreen) {
                            "splash" -> {
                                com.example.twopchat.ui.main.AnimatedSplashScreen(
                                    primaryColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            "disguise" -> {
                                CurrencyRatesScreen(
                                    appLanguage = appLanguage,
                                    onUnlock = {
                                        isStealthDisguiseLockedState.value = false
                                        val hasPasscode = sharedPrefs.getBoolean("settings_passcode", false) &&
                                            !sharedPrefs.getString("passcode_value", null).isNullOrEmpty()
                                        if (hasPasscode) {
                                            isAppLockedState.value = true
                                        }
                                    }
                                )
                            }
                            "unlock" -> {
                                PasscodeUnlockScreen(
                                    appLanguage = appLanguage,
                                    primaryColor = MaterialTheme.colorScheme.primary,
                                    surfaceColor = MaterialTheme.colorScheme.surface,
                                    onSurfaceColor = MaterialTheme.colorScheme.onSurface,
                                    onUnlock = {
                                        isAppLockedState.value = false
                                        P2PPreferences.setAppLocked(false)
                                        lastInteractionTime = System.currentTimeMillis()
                                        pauseTime = 0L
                                    },
                                    onDuressTriggered = {
                                        if (!AccountLifecycle.deleteAccount(applicationContext)) {
                                            SafeLog.e(
                                                "MainActivity",
                                                "Duress wipe aborted because the P2P runtime did not stop cleanly",
                                            )
                                            return@PasscodeUnlockScreen
                                        }

                                        try {
                                            setAppIconAlias("MainActivityAliasDefault")
                                        } catch (e: Exception) {
                                            SafeLog.e("MainActivity", "Failed to reset icon on duress", e)
                                        }
                                        isDarkTheme = true
                                        accentScheme = "mint"
                                        useCerulean = false
                                        useAmoled = false
                                        appLanguage = "English"
                                        P2PPreferences.setAppLanguage(applicationContext, "English")
                                        isAppLockedState.value = false
                                        P2PPreferences.setAppLocked(false)
                                        recreate()
                                    }
                                )
                            }
                            "main" -> {
                                MainNavigation(
                                    isDarkTheme = isDarkTheme,
                                    onThemeChanged = { dark ->
                                        isDarkTheme = dark
                                        sharedPrefs.edit().putString("theme_mode", if (dark) "dark" else "light").apply()
                                    },
                                    useCerulean = useCerulean,
                                    onAccentChanged = { cerulean ->
                                        val newScheme = if (cerulean) "cerulean" else if (accentScheme == "cerulean") "mint" else accentScheme
                                        accentScheme = newScheme
                                        useCerulean = (newScheme == "cerulean")
                                        sharedPrefs.edit().putString("accent_scheme", newScheme).putBoolean("use_cerulean", cerulean).apply()
                                    },
                                    accentScheme = accentScheme,
                                    onAccentSchemeChanged = { scheme ->
                                        accentScheme = scheme
                                        useCerulean = (scheme == "cerulean")
                                        sharedPrefs.edit().putString("accent_scheme", scheme).putBoolean("use_cerulean", scheme == "cerulean").apply()
                                    },
                                    useAmoled = useAmoled,
                                    onAmoledChanged = { amoled ->
                                        useAmoled = amoled
                                        sharedPrefs.edit().putBoolean("use_amoled", amoled).apply()
                                    },
                                    appLanguage = appLanguage,
                                    onLanguageChanged = { lang ->
                                        appLanguage = lang
                                        P2PPreferences.setAppLanguage(this@MainActivity, lang)
                                    },
                                    onIconChanged = { aliasName ->
                                        setAppIconAlias(aliasName)
                                    }
                                )
                            }
                        }
                    }
                }
            } 
        }
    }
}

    override fun onDestroy() {
        if (::appPreferences.isInitialized) {
            appPreferences.unregisterOnSharedPreferenceChangeListener(motionPreferenceListener)
        }
        contentResolver.unregisterContentObserver(systemAnimationScaleObserver)
        super.onDestroy()
    }

    private fun setAppIconAlias(aliasName: String) {
        val pm = packageManager
        val aliases = listOf(
            "com.example.twopchat.MainActivityAliasDefault",
            "com.example.twopchat.MainActivityAliasBlue",
            "com.example.twopchat.MainActivityAliasNoir",
            "com.example.twopchat.MainActivityAliasNeon",
            "com.example.twopchat.MainActivityAliasCurrency"
        )

        val targetAlias = "com.example.twopchat.$aliasName"

        for (alias in aliases) {
            val state = if (alias == targetAlias) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(this, alias),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                SafeLog.e("MainActivity", "Failed to toggle alias $alias", e)
            }
        }
    }

}

// Full Screen Secure Passcode Pinpad Unlock
@Composable
fun PasscodeUnlockScreen(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onUnlock: () -> Unit,
    onDuressTriggered: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }
    var inputPin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val lockPrefs = remember { context.getSharedPreferences("2pchat_lock_state", android.content.Context.MODE_PRIVATE) }
    var failedAttempts by remember { mutableStateOf(lockPrefs.getInt("failed_attempts", 0)) }
    var lockoutUntil by remember { mutableStateOf(lockPrefs.getLong("lockout_until", 0L)) }
    var lockoutTimeRemaining by remember {
        mutableStateOf(((lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L).toInt())
    }

    fun triggerKeyHaptic() {
        if (sharedPrefs.getBoolean("settings_haptic_feedback", true)) {
            val performed = view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            if (!performed) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun triggerErrorHaptic() {
        if (sharedPrefs.getBoolean("settings_haptic_feedback", true)) {
            val performed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                view.performHapticFeedback(
                    HapticFeedbackConstants.REJECT,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            } else {
                false
            }
            if (!performed) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    fun triggerSuccessHaptic() {
        if (sharedPrefs.getBoolean("settings_haptic_feedback", true)) {
            val performed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                view.performHapticFeedback(
                    HapticFeedbackConstants.CONFIRM,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            } else {
                false
            }
            if (!performed) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    LaunchedEffect(lockoutTimeRemaining > 0) {
        if (lockoutUntil > System.currentTimeMillis()) {
            while (lockoutUntil > System.currentTimeMillis()) {
                kotlinx.coroutines.delay(1000)
                lockoutTimeRemaining = ((lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L).toInt()
            }
            lockoutTimeRemaining = 0
            lockPrefs.edit().remove("lockout_until").apply()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(30.dp))

        // Branding Logo
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(90.dp)
                    .background(primaryColor.copy(alpha = 0.12f), shape = CircleShape)
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_logo_default_fg),
                    contentDescription = "Logo",
                    modifier = Modifier.size(60.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(primaryColor)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = Localizations.getString("unlock_app", appLanguage),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
            Text(
                text = if (lockoutTimeRemaining > 0) {
                    if (appLanguage == "Русский") "Слишком много попыток. Экран заблокирован на $lockoutTimeRemaining с"
                    else "Too many attempts. Keypad locked for $lockoutTimeRemaining s"
                } else if (showError) {
                    Localizations.getString("wrong_pin", appLanguage)
                } else {
                    Localizations.getString("enter_pin_to_unlock", appLanguage)
                },
                fontSize = 14.sp,
                color = if (lockoutTimeRemaining > 0 || showError) Color.Red else onSurfaceColor.copy(alpha = 0.6f)
            )
        }

        // PIN Indicators (4 Dots)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 4) {
                val isFilled = i < inputPin.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (isFilled) primaryColor else Color.Transparent,
                            shape = CircleShape
                        )
                        .border(1.5.dp, if (showError) Color.Red else primaryColor, CircleShape)
                )
            }
        }

        // Custom Numeric Pinpad (3x4 Grid)
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 40.dp)
        ) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("C", "0", "⌫")
            )

            keys.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { digit ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(72.dp)
                                .background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.1f), CircleShape)
                                .clickable {
                                    if (lockoutTimeRemaining > 0) return@clickable
                                    triggerKeyHaptic()
                                    showError = false
                                    when (digit) {
                                        "C" -> inputPin = ""
                                        "⌫" -> if (inputPin.isNotEmpty()) {
                                            inputPin = inputPin.dropLast(1)
                                        }
                                        else -> {
                                            if (inputPin.length < 4) {
                                                inputPin += digit
                                                if (inputPin.length == 4) {
                                                    val correctPasscode = sharedPrefs.getString("passcode_value", "") ?: ""
                                                    val duressPasscode = sharedPrefs.getString("passcode_duress_value", "") ?: ""
                                                    
                                                    if (SecurityUtils.verifyAndMigratePasscode(inputPin, correctPasscode, sharedPrefs, "passcode_value")) {
                                                        triggerSuccessHaptic()
                                                        failedAttempts = 0
                                                        lockPrefs.edit().clear().apply()
                                                        onUnlock()
                                                    } else if (duressPasscode.isNotEmpty() && SecurityUtils.verifyAndMigratePasscode(inputPin, duressPasscode, sharedPrefs, "passcode_duress_value")) {
                                                        triggerSuccessHaptic()
                                                        onDuressTriggered()
                                                    } else {
                                                        triggerErrorHaptic()
                                                        failedAttempts += 1
                                                        lockPrefs.edit().putInt("failed_attempts", failedAttempts).apply()
                                                        if (failedAttempts >= 5) {
                                                            lockoutUntil = System.currentTimeMillis() + 30_000L
                                                            lockoutTimeRemaining = 30
                                                            failedAttempts = 0
                                                            lockPrefs.edit()
                                                                .putInt("failed_attempts", 0)
                                                                .putLong("lockout_until", lockoutUntil)
                                                                .apply()
                                                        }
                                                        showError = true
                                                        inputPin = ""
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            Text(
                                text = digit,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onSurfaceColor
                            )
                        }
                    }
                }
            }
        }
    }
}
