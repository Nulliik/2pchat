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
import android.content.pm.PackageManager
import android.widget.Toast
import com.example.twopchat.theme.StealthBlack
import com.example.twopchat.data.Localizations
import com.example.twopchat.ui.disguise.CurrencyRatesScreen

class MainActivity : ComponentActivity() {
    private var lastInteractionTime = System.currentTimeMillis()
    private var lastStopTime = System.currentTimeMillis()
    private val triggerLockCheckState = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Secure screen from screenshots based on user preferences
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

        // Initialize Chaquopy Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        // Initialize PythonBridge
        PythonBridge.init(applicationContext)

        val sharedPrefs = getSharedPreferences("2pchat_prefs", MODE_PRIVATE)
        PythonBridge.setVerboseLogging(sharedPrefs.getBoolean("settings_python_verbose", false))
        
        // Start background P2P Message Server
        P2PMessageRelay.startServer(applicationContext)

        enableEdgeToEdge()
        
        setContent {
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1000) // 1 second display
                showSplash = false
            }

            var isDarkTheme by remember { mutableStateOf(sharedPrefs.getString("theme_mode", "dark") == "dark") }
            var useCerulean by remember { mutableStateOf(sharedPrefs.getBoolean("use_cerulean", false)) }
            var appLanguage by remember { mutableStateOf(sharedPrefs.getString("settings_language", "English") ?: "English") }
            
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

            _2PChatTheme(darkTheme = isDarkTheme, useCerulean = useCerulean) { 
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { 
                    if (showSplash) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.splash_background),
                            contentDescription = "Splash Screen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else if (isStealthDisguiseLocked && sharedPrefs.getBoolean("settings_stealth_disguise", false)) {
                        CurrencyRatesScreen(
                            appLanguage = appLanguage,
                            onUnlock = { isStealthDisguiseLocked = false }
                        )
                    } else if (isAppLocked && passcodeVal.isNotEmpty()) {
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
                                 sharedPrefs.edit().clear().apply()
                                 try {
                                     filesDir.listFiles()?.forEach { it.deleteRecursively() }
                                     cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                                 } catch (e: Exception) {
                                     android.util.Log.e("MainActivity", "Failed to clear files on duress", e)
                                 }
                                 try {
                                     setAppIconAlias("MainActivityAliasDefault")
                                 } catch (e: Exception) {
                                     android.util.Log.e("MainActivity", "Failed to reset icon on duress", e)
                                 }
                                 isDarkTheme = true
                                 useCerulean = false
                                 appLanguage = "English"
                                 isAppLocked = false
                             }
                        )
                    } else {
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

    override fun onDestroy() {
        super.onDestroy()
        P2PMessageRelay.stopServer()
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
    var failedAttempts by remember { mutableStateOf(0) }
    var lockoutTimeRemaining by remember { mutableStateOf(0) }

    LaunchedEffect(lockoutTimeRemaining) {
        if (lockoutTimeRemaining > 0) {
            kotlinx.coroutines.delay(1000)
            lockoutTimeRemaining -= 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StealthBlack) // Always dark/secure background for lock screens
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
                color = Color.White
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
                color = if (lockoutTimeRemaining > 0 || showError) Color.Red else Color.LightGray
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
                                .background(Color.White.copy(alpha = 0.05f), shape = CircleShape)
                                .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
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
                                                        onUnlock()
                                                    } else if (duressPasscode.isNotEmpty() && SecurityUtils.verifyAndMigratePasscode(inputPin, duressPasscode, sharedPrefs, "passcode_duress_value")) {
                                                        onDuressTriggered()
                                                    } else {
                                                        failedAttempts += 1
                                                        if (failedAttempts >= 5) {
                                                            lockoutTimeRemaining = 30
                                                            failedAttempts = 0
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
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
