package com.example.twopchat.ui.main

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.theme.*

data class ThemePreset(
    val id: String,
    val titleRu: String,
    val titleEn: String,
    val isDark: Boolean,
    val accentScheme: String,
    val useAmoled: Boolean,
    val previewBg: Color,
    val previewIncomingBg: Color,
    val previewIncomingText: Color,
    val previewOutgoingBg: Color,
    val previewOutgoingText: Color,
)

@Composable
fun AppearanceSettingsPage(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    accentScheme: String,
    onAccentSchemeChanged: (String) -> Unit,
    useAmoled: Boolean,
    onAmoledChanged: (Boolean) -> Unit,
    activeIconAlias: String,
    onIconChanged: (String) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }

    var showThemesPicker by remember { mutableStateOf(false) }
    var showLauncherIconsPicker by remember { mutableStateOf(false) }
    var reduceMotion by remember {
        mutableStateOf(sharedPrefs.getBoolean(com.example.twopchat.REDUCE_MOTION_SETTING, false))
    }
    var persistChatHistory by remember {
        mutableStateOf(sharedPrefs.getBoolean("persist_chat_history", true))
    }
    var linkPreviewsEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_link_previews", true))
    }
    var heroWidgetCollapsed by remember {
        mutableStateOf(sharedPrefs.getBoolean("settings_hero_widget_collapsed", false))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubPageLayout(
            title = Localizations.tr(appLanguage, "Тема и Оформление", "Appearance & Theme", "Erscheinungsbild & Theme", "Apariencia y Tema", "Apparence et Thème", "Aparência e Tema"),
            appLanguage = appLanguage,
            onBackClick = onBackClick,
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

@Composable
fun VisualThemeSelector(
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
