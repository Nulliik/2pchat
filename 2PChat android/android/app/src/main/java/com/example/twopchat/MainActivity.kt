package com.example.twopchat

import android.os.Bundle
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
import com.example.twopchat.theme._2PChatTheme
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
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
    private var lastStopTime = System.currentTimeMillis()
    private val triggerLockCheckState = mutableStateOf(0)
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
        val sharedPrefsTemp = getSharedPreferences("2pchat_prefs", MODE_PRIVATE)
        val blockScreenshots = sharedPrefsTemp.getBoolean("settings_screenshots", true)
        if (blockScreenshots) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenSecurity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenSecurity()

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Initialize Chaquopy Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        // Initialize PythonBridge
        PythonBridge.init(applicationContext)
        
        // The foreground service, rather than this Activity, owns the P2P listener.
        androidx.core.content.ContextCompat.startForegroundService(
            this,
            Intent(this, P2PRelayService::class.java),
        )

        // Start Yggdrasil VPN service automatically if enabled and prepared
        val yggPrefs = getSharedPreferences("2pchat_prefs", MODE_PRIVATE)
        if (yggPrefs.getBoolean("settings_yggdrasil", true)) {
            if (android.net.VpnService.prepare(applicationContext) == null) {
                val yggIntent = Intent(applicationContext, com.example.twopchat.yggdrasil.PacketTunnelProvider::class.java).apply {
                    action = com.example.twopchat.yggdrasil.PacketTunnelProvider.ACTION_START
                }
                try {
                    startService(yggIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val sharedPrefs = getSharedPreferences("2pchat_prefs", MODE_PRIVATE)
        appPreferences = sharedPrefs
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
            LaunchedEffect(isDarkTheme) {
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
            var useCerulean by remember { mutableStateOf(sharedPrefs.getBoolean("use_cerulean", false)) }
            var useAmoled by remember { mutableStateOf(sharedPrefs.getBoolean("use_amoled", false)) }
            val systemDefaultLanguage = if (java.util.Locale.getDefault().language == "ru") "Русский" else "English"
            var appLanguage by remember { mutableStateOf(sharedPrefs.getString("settings_language", systemDefaultLanguage) ?: systemDefaultLanguage) }
            
            var isAppLocked by remember { mutableStateOf(sharedPrefs.getBoolean("settings_passcode", false)) }
            val passcodeVal = remember(isAppLocked) { sharedPrefs.getString("passcode_value", "") ?: "" }
            val duressPinVal = remember(isAppLocked) { sharedPrefs.getString("passcode_duress_value", "") ?: "" }
            var isStealthDisguiseLocked by remember { mutableStateOf(sharedPrefs.getBoolean("settings_stealth_disguise", false)) }

            // Check auto-lock on app start/resume
            val triggerLockCheck by remember { triggerLockCheckState }
            LaunchedEffect(triggerLockCheck) {
                if (sharedPrefs.getBoolean("settings_stealth_disguise", false)) {
                    isStealthDisguiseLocked = true
                }
                if (sharedPrefs.getBoolean("settings_passcode", false)) {
                    val elapsed = System.currentTimeMillis() - lastStopTime
                    val timeoutMinutes = sharedPrefs.getInt("passcode_autolock_minutes", 1)
                    if (elapsed >= timeoutMinutes * 60 * 1000) {
                        isAppLocked = true
                    }
                }
            }

            // Check inactivity timer during in-app usage
            LaunchedEffect(isAppLocked) {
                if (sharedPrefs.getBoolean("settings_passcode", false) && !isAppLocked) {
                    while (true) {
                        kotlinx.coroutines.delay(5000) // check every 5 seconds
                        val timeoutMinutes = sharedPrefs.getInt("passcode_autolock_minutes", 1)
                        val elapsed = System.currentTimeMillis() - lastInteractionTime
                        if (elapsed >= timeoutMinutes * 60 * 1000) {
                            isAppLocked = true
                            break
                        }
                    }
                }
            }

            LaunchedEffect(isAppLocked) {
                if (isAppLocked) {
                    SecureStorage.clearDbPassphrase()
                    com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
                }
            }

            _2PChatTheme(
                darkTheme = isDarkTheme,
                useCerulean = useCerulean,
                useAmoled = useAmoled,
                animationsEnabled = !reduceMotionState.value,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { 
                    val currentScreen = when {
                        showSplash -> "splash"
                        isStealthDisguiseLocked && sharedPrefs.getBoolean("settings_stealth_disguise", false) -> "disguise"
                        isAppLocked && passcodeVal.isNotEmpty() -> "unlock"
                        else -> "main"
                    }
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        },
                        label = "screen_transition",
                        modifier = Modifier.fillMaxSize()
                    ) { targetScreen ->
                        when (targetScreen) {
                            "splash" -> {
                                Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.splash_background),
                                    contentDescription = "Splash Screen",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                            "disguise" -> {
                                CurrencyRatesScreen(
                                    appLanguage = appLanguage,
                                    onUnlock = { isStealthDisguiseLocked = false }
                                )
                            }
                            "unlock" -> {
                                PasscodeUnlockScreen(
                                    appLanguage = appLanguage,
                                    primaryColor = MaterialTheme.colorScheme.primary,
                                    surfaceColor = MaterialTheme.colorScheme.surface,
                                    onSurfaceColor = MaterialTheme.colorScheme.onSurface,
                                    correctPasscode = passcodeVal,
                                    duressPasscode = duressPinVal,
                                    onUnlock = {
                                        isAppLocked = false
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    onDuressTriggered = {
                                        if (!AccountLifecycle.deleteAccount(applicationContext)) {
                                            android.util.Log.e(
                                                "MainActivity",
                                                "Duress wipe aborted because the P2P runtime did not stop cleanly",
                                            )
                                            return@PasscodeUnlockScreen
                                        }

                                        try {
                                            setAppIconAlias("MainActivityAliasDefault")
                                        } catch (e: Exception) {
                                            android.util.Log.e("MainActivity", "Failed to reset icon on duress", e)
                                        }
                                        isDarkTheme = true
                                        useCerulean = false
                                        useAmoled = false
                                        appLanguage = "English"
                                        isAppLocked = false
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
                                        useCerulean = cerulean
                                        sharedPrefs.edit().putBoolean("use_cerulean", cerulean).apply()
                                    },
                                    useAmoled = useAmoled,
                                    onAmoledChanged = { amoled ->
                                        useAmoled = amoled
                                        sharedPrefs.edit().putBoolean("use_amoled", amoled).apply()
                                    },
                                    appLanguage = appLanguage,
                                    onLanguageChanged = { lang ->
                                        appLanguage = lang
                                        sharedPrefs.edit().putString("settings_language", lang).apply()
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

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTime = System.currentTimeMillis()
    }

    override fun onStop() {
        super.onStop()
        lastStopTime = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        triggerLockCheckState.value += 1
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
                android.util.Log.e("MainActivity", "Failed to toggle alias $alias", e)
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
    correctPasscode: String,
    duressPasscode: String,
    onUnlock: () -> Unit,
    onDuressTriggered: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var inputPin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val lockPrefs = remember { context.getSharedPreferences("2pchat_lock_state", android.content.Context.MODE_PRIVATE) }
    var failedAttempts by remember { mutableStateOf(lockPrefs.getInt("failed_attempts", 0)) }
    var lockoutUntil by remember { mutableStateOf(lockPrefs.getLong("lockout_until", 0L)) }
    var lockoutTimeRemaining by remember {
        mutableStateOf(((lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L).toInt())
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
                                                    val sharedPrefs = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                                                    
                                                    if (SecurityUtils.verifyAndMigratePasscode(inputPin, correctPasscode, sharedPrefs, "passcode_value")) {
                                                        failedAttempts = 0
                                                        lockPrefs.edit().clear().apply()
                                                        onUnlock()
                                                    } else if (duressPasscode.isNotEmpty() && SecurityUtils.verifyAndMigratePasscode(inputPin, duressPasscode, sharedPrefs, "passcode_duress_value")) {
                                                        onDuressTriggered()
                                                    } else {
                                                        failedAttempts += 1
                                                        lockPrefs.edit().putInt("failed_attempts", failedAttempts).apply()
                                                        if (failedAttempts >= 5) {
                                                            lockoutUntil = System.currentTimeMillis() + 30_000L
                                                            lockoutTimeRemaining = 30
                                                            failedAttempts = 0
                                                            lockPrefs.edit()
                                                                .putInt("failed_attempts", 0)
                                                                .putLong("lockout_until", lockoutUntil)
                                                                .commit()
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
