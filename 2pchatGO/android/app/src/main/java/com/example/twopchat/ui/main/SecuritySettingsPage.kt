package com.example.twopchat.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.security.RootDetectionHelper
import com.example.twopchat.theme.MintGreen
import com.example.twopchat.theme.StealthBlack

@Composable
fun SecuritySettingsPage(
    localFingerprint: String,
    formattedLocalFingerprint: String,
    activeIconAlias: String,
    onIconChanged: (String) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    surfaceVariant: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit,
    onRequestDisguiseDialog: () -> Unit,
    onRequestPasscodeDialog: () -> Unit,
    onRequestDisablePasscodeDialog: () -> Unit,
    onRequestAutolockDialog: () -> Unit,
    onRequestDuressDialog: () -> Unit,
    onRequestSeedBackupDialog: () -> Unit,
    onRequestPinForBackupDialog: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }

    var stealthDisguise by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_stealth_disguise", false))
    }
    var blockScreenshots by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_screenshots", false))
    }
    var incognitoKeyboard by remember {
        mutableStateOf(P2PPreferences.isIncognitoKeyboardEnabled(context))
    }
    var passcodeLock by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_passcode", false))
    }
    val autolockMinutes by remember {
        derivedStateOf { sharedPrefs.getInt("passcode_autolock_minutes", 5) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubPageLayout(
            title = Localizations.tr(appLanguage, "Безопасность и Доступ", "Security & Access", "Sicherheit & Zugriff", "Seguridad y Acceso", "Sécurité et Accès", "Segurança e Acesso", tr = "Güvenlik ve Erişim"),
            appLanguage = appLanguage,
            onBackClick = onBackClick,
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
                            text = Localizations.tr(appLanguage, "Личный ключ безопасности", "Personal Security Key", "Persönlicher Sicherheitsschlüssel", "Huella de seguridad personal", "Empreinte de sécurité personnelle", "Impressão digital de segurança pessoal", tr = "Kişisel Güvenlik Anahtarı"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = Localizations.tr(appLanguage, "Сверьте эту строку с собеседником по доверенному каналу для защиты от подмены ключей (MITM) и подтверждения личности.", "Compare this string with your contact over a trusted channel to verify identity and protect against MITM.", "Gleichen Sie diese Zeichenkette mit Ihrem Kontakt über einen vertrauenswürdigen Kanal ab.", "Compare esta cadena con su contacto a través de un canal de confianza para verificar identidad.", "Comparez cette chaîne avec votre contact via un canal de confiance pour vérifier l'identité.", "Compare esta string com seu contato por um canal confiável.", tr = "Kimliği doğrulamak ve MITM saldırılarına karşı korunmak için bu dizeyi eşinizle güvenilir bir kanal üzerinden karşılaştırın."),
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
                                text = if (formattedLocalFingerprint.isNotBlank()) {
                                    formattedLocalFingerprint
                                } else {
                                    Localizations.tr(
                                        appLanguage,
                                        ru = "Инициализация ключа...",
                                        en = "Initializing key...",
                                        de = "Schlüssel wird initialisiert...",
                                        es = "Inicializando clave...",
                                        fr = "Initialisation de la clé...",
                                        pt = "Inicializando chave...",
                                        tr = "Anahtar başlatılıyor..."
                                    )
                                },
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
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("2PChat Security Key", localFingerprint)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    context,
                                    Localizations.tr(appLanguage, "Личный ключ скопирован", "Security key copied", "Sicherheitsschlüssel kopiert", "Clave de seguridad copiada", "Clé de sécurité copiée", "Chave de segurança copiada", tr = "Güvenlik anahtarı kopyalandı"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = Localizations.tr(appLanguage, "Скопировать ключ", "Copy Security Key", "Schlüssel kopieren", "Copiar clave", "Copier la clé", "Copiar chave", tr = "Güvenlik Anahtarını Kopyala"),
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
                                    onRequestDisguiseDialog()
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
                                    onRequestPasscodeDialog()
                                } else {
                                    onRequestDisablePasscodeDialog()
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
                                .clickable { onRequestAutolockDialog() }
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
                                .clickable { onRequestDuressDialog() }
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

                    val isRootedDevice = remember { RootDetectionHelper.isRooted() }
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
                                    onRequestPinForBackupDialog()
                                } else {
                                    onRequestSeedBackupDialog()
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
