package com.example.twopchat.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.twopchat.NativeBridge
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.security.SecurityUtils
import com.example.twopchat.theme.MintGreen
import com.example.twopchat.theme.StealthBlack
import com.example.twopchat.ui.common.FullScreenAvatarViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsModalsAndDialogs(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    surfaceVariant: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    username: String,
    profileBitmap: Bitmap?,
    fullProfileBitmap: Bitmap?,
    onPickNewAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    showFullScreenAvatar: Boolean,
    onDismissFullScreenAvatar: () -> Unit,
    showAvatarOptions: Boolean,
    onDismissAvatarOptions: () -> Unit,
    onOpenFullScreenAvatar: () -> Unit,
    showLanguageDialog: Boolean,
    onDismissLanguageDialog: () -> Unit,
    onLanguageChanged: (String) -> Unit,
    showDisguiseInstructionDialog: Boolean,
    onDismissDisguiseInstructionDialog: () -> Unit,
    showPinForBackupDialog: Boolean,
    onDismissPinForBackupDialog: () -> Unit,
    onPinForBackupConfirmed: () -> Unit,
    showSeedBackupDialog: Boolean,
    onDismissSeedBackupDialog: () -> Unit,
    showSetPasscodeDialog: Boolean,
    onDismissSetPasscodeDialog: () -> Unit,
    onPasscodeSetSuccess: () -> Unit,
    showDisablePasscodeDialog: Boolean,
    onDismissDisablePasscodeDialog: () -> Unit,
    onPasscodeDisabledSuccess: () -> Unit,
    showAutolockDialog: Boolean,
    onDismissAutolockDialog: () -> Unit,
    autolockMinutes: Int,
    onAutolockMinutesChanged: (Int) -> Unit,
    showDeleteAccountDialog: Boolean,
    onDismissDeleteAccountDialog: () -> Unit,
    onDeleteAccountConfirmed: () -> Unit,
    showSetDuressDialog: Boolean,
    onDismissSetDuressDialog: () -> Unit,
    showEditAboutMeDialog: Boolean,
    onDismissEditAboutMeDialog: () -> Unit,
    aboutMeText: String,
    onAboutMeSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }

    // ── Full Screen Avatar Viewer ─────────────────────────────────────────
    if (showFullScreenAvatar) {
        FullScreenAvatarViewer(
            title = username,
            bitmap = fullProfileBitmap ?: profileBitmap,
            initials = username.take(2).uppercase(),
            avatarColor = primaryColor,
            onDismiss = onDismissFullScreenAvatar
        )
    }

    // ── Avatar Options Sheet ──────────────────────────────────────────────
    if (showAvatarOptions) {
        AlertDialog(
            onDismissRequest = onDismissAvatarOptions,
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissAvatarOptions) {
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
                            onDismissAvatarOptions()
                            onOpenFullScreenAvatar()
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
                            onDismissAvatarOptions()
                            onPickNewAvatar()
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
                            onDismissAvatarOptions()
                            onRemoveAvatar()
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

    // ── Language Selection Dialog ─────────────────────────────────────────
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
            onDismissRequest = onDismissLanguageDialog,
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissLanguageDialog) {
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
                                    onDismissLanguageDialog()
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
                                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_check_bold),
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

    // ── Stealth Disguise Instructions ─────────────────────────────────────
    if (showDisguiseInstructionDialog) {
        AlertDialog(
            onDismissRequest = onDismissDisguiseInstructionDialog,
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
                TextButton(onClick = onDismissDisguiseInstructionDialog) {
                    Text(text = if (appLanguage == "Русский") "Понятно" else "Understood", color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── PIN Verification Dialog before showing Seed Phrase ────────────────
    if (showPinForBackupDialog) {
        var verifyPin by remember { mutableStateOf("") }
        var verifyPinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                onDismissPinForBackupDialog()
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
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
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
                            onDismissPinForBackupDialog()
                            verifyPin = ""
                            verifyPinError = false
                            onPinForBackupConfirmed()
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
                    onDismissPinForBackupDialog()
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

    // ── Seed Phrase Backup Dialog ─────────────────────────────────────────
    if (showSeedBackupDialog) {
        val mnemonic = remember { NativeBridge.getLocalSeedMnemonic() ?: "" }
        val words = remember(mnemonic) {
            if (mnemonic.isNotBlank()) mnemonic.split(" ") else emptyList()
        }

        AlertDialog(
            onDismissRequest = onDismissSeedBackupDialog,
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
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("2PChat Recovery Phrase", mnemonic)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                clip.description.extras = PersistableBundle().apply {
                                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, Localizations.getString("phrase_copied", appLanguage), Toast.LENGTH_SHORT).show()

                            // Auto-clear clipboard after 45 seconds
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(45_000L)
                                runCatching {
                                    if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == mnemonic) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                            clipboard.clearPrimaryClip()
                                        } else {
                                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
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
                TextButton(onClick = onDismissSeedBackupDialog) {
                    Text(Localizations.getString("close", appLanguage), color = onSurfaceVariant)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── Passcode Setup Dialog ─────────────────────────────────────────────
    if (showSetPasscodeDialog) {
        var pin1 by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        var isConfirming by remember { mutableStateOf(false) }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissSetPasscodeDialog,
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
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            keyboardType = KeyboardType.NumberPassword,
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
                                onPasscodeSetSuccess()
                                onDismissSetPasscodeDialog()
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
                TextButton(onClick = onDismissSetPasscodeDialog) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── Disable Passcode Dialog ───────────────────────────────────────────
    if (showDisablePasscodeDialog) {
        var enteredPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissDisablePasscodeDialog,
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
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            keyboardType = KeyboardType.NumberPassword,
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
                            onPasscodeDisabledSuccess()
                            onDismissDisablePasscodeDialog()
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
                TextButton(onClick = onDismissDisablePasscodeDialog) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── Inactivity Autolock Selector Dialog ────────────────────────────────
    if (showAutolockDialog) {
        val options = listOf(1, 5, 10, 30)
        AlertDialog(
            onDismissRequest = onDismissAutolockDialog,
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissAutolockDialog) {
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
                                    onAutolockMinutesChanged(minutes)
                                    sharedPrefs.edit().putInt("passcode_autolock_minutes", minutes).apply()
                                    onDismissAutolockDialog()
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

    // ── Delete Account Confirmation Dialog ────────────────────────────────
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = onDismissDeleteAccountDialog,
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
                        onDismissDeleteAccountDialog()
                        onDeleteAccountConfirmed()
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
                TextButton(onClick = onDismissDeleteAccountDialog) {
                    Text(Localizations.getString("cancel", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── Set Duress PIN Dialog Flow ────────────────────────────────────────
    if (showSetDuressDialog) {
        var duressPin1 by remember { mutableStateOf("") }
        var duressPin2 by remember { mutableStateOf("") }
        var isDuressConfirming by remember { mutableStateOf(false) }
        var duressPinError by remember { mutableStateOf(false) }
        var duressMatchesMainError by remember { mutableStateOf(false) }

        val mainPinVal = sharedPrefs.getString("passcode_value", "") ?: ""

        AlertDialog(
            onDismissRequest = onDismissSetDuressDialog,
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
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            keyboardType = KeyboardType.NumberPassword,
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
                                onDismissSetDuressDialog()
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
                    onDismissSetDuressDialog()
                    Toast.makeText(context, Localizations.getString("duress_disabled", appLanguage), Toast.LENGTH_SHORT).show()
                }) {
                    Text(Localizations.getString("disable", appLanguage), color = Color.Red)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── Edit About Me Dialog ──────────────────────────────────────────────
    if (showEditAboutMeDialog) {
        var tempText by remember { mutableStateOf(aboutMeText) }
        AlertDialog(
            onDismissRequest = onDismissEditAboutMeDialog,
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
                            capitalization = KeyboardCapitalization.Sentences,
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
                        val newText = tempText.trim()
                        sharedPrefs.edit().putString("about_me_profile", newText).apply()
                        onAboutMeSaved(newText)
                        onDismissEditAboutMeDialog()
                        
                        // Update identity & announce
                        val bridge = P2PBridgeProvider.get(context)
                        val localFingerprint = bridge.getLocalFingerprint()
                        bridge.configureLocalIdentity(username, localFingerprint, newText)
                        P2PMessageRelay.refreshAnnouncement(context)
                    }
                ) {
                    Text(if (appLanguage == "Русский") "Сохранить" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissEditAboutMeDialog) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            }
        )
    }
}
