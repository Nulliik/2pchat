package com.example.twopchat.ui.onboarding

import com.example.twopchat.logging.SafeLog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.VpnService
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import com.example.twopchat.config.*
import com.example.twopchat.NativeBridge
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.data.Localizations
import com.example.twopchat.data.ProfileBackupManager
import com.example.twopchat.security.Bip39Dictionary
import com.example.twopchat.security.RestoreAttemptTracker
import com.example.twopchat.theme.*
import com.example.twopchat.yggdrasil.PacketTunnelProvider

@Composable
fun OnboardingScreen(
    appLanguage: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }
    
    var currentStep by remember { mutableIntStateOf(1) }
    var nickname by remember { mutableStateOf("") }
    var profilePhotoUri by remember { mutableStateOf<String?>(null) }
    var profileBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var showYggdrasilDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    fun startYggdrasilAndComplete(enableYggdrasil: Boolean = true) {
        showYggdrasilDialog = false
        sharedPrefs.edit().putBoolean("settings_yggdrasil", enableYggdrasil).apply()
        if (enableYggdrasil) {
            try {
                com.example.twopchat.yggdrasil.YggdrasilCoordinator.start(context)
            } catch (error: Exception) {
                SafeLog.e("OnboardingScreen", "Unable to start Yggdrasil service", error)
                sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
            }
        }
        onComplete()
    }

    LaunchedEffect(profilePhotoUri) {
        profileBitmap = if (profilePhotoUri != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadBitmapFromUri(context, profilePhotoUri)
            }
        } else {
            null
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startYggdrasilAndComplete(enableYggdrasil = true)
        } else {
            // User dismissed or denied VPN prompt, proceed into app with Yggdrasil disabled
            startYggdrasilAndComplete(enableYggdrasil = false)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pendingCropUri = it
        }
    }

    var fingerprint by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        if (!NativeBridge.isLoaded) {
            NativeBridge.initialize()
        }
        fingerprint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            P2PBridgeProvider.get(context).getLocalFingerprint()
        }
    }
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val cropUri = pendingCropUri
    if (cropUri != null) {
        ImageCropper(
            imageUri = cropUri,
            onCropSuccess = { localPath ->
                profilePhotoUri = localPath
                sharedPrefs.edit().putString("profile_photo_uri", localPath).apply()
                pendingCropUri = null
            },
            onCancel = {
                pendingCropUri = null
            },
            appLanguage = appLanguage
        )
        return
    }

    // Yggdrasil Activation Prompt Dialog (Step 5 trigger)
    if (showYggdrasilDialog) {
        AlertDialog(
            onDismissRequest = {
                showYggdrasilDialog = false
                startYggdrasilAndComplete(enableYggdrasil = false)
            },
            title = {
                Text(
                    text = Localizations.getString("enable_yggdrasil_prompt_title", appLanguage),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = onSurfaceColor
                )
            },
            text = {
                Text(
                    text = Localizations.getString("enable_yggdrasil_prompt_desc", appLanguage),
                    fontSize = 14.sp,
                    color = onSurfaceColor.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showYggdrasilDialog = false
                        val vpnPrepareIntent = try {
                            VpnService.prepare(context)
                        } catch (e: Exception) {
                            null
                        }
                        if (vpnPrepareIntent != null) {
                            try {
                                vpnPermissionLauncher.launch(vpnPrepareIntent)
                            } catch (e: Exception) {
                                SafeLog.e("OnboardingScreen", "Failed to launch VPN permission intent", e)
                                startYggdrasilAndComplete(enableYggdrasil = false)
                            }
                        } else {
                            startYggdrasilAndComplete(enableYggdrasil = true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = Localizations.getString("enable_vpn_btn", appLanguage),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showYggdrasilDialog = false
                        startYggdrasilAndComplete(enableYggdrasil = false)
                    }
                ) {
                    Text(
                        text = Localizations.getString("skip_for_now", appLanguage),
                        color = onSurfaceColor.copy(alpha = 0.6f)
                    )
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Account Restore Dialog
    if (showRestoreDialog) {
        AccountRestoreModal(
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            sharedPrefs = sharedPrefs,
            onDismiss = { showRestoreDialog = false },
            onSuccess = {
                showRestoreDialog = false
                onComplete()
            }
        )
    }

    val contentScrollState = rememberScrollState()

    LaunchedEffect(currentStep) {
        contentScrollState.scrollTo(0)
    }

    LaunchedEffect(nickname) {
        if (currentStep == 4) {
            contentScrollState.animateScrollTo(contentScrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Step Indicators (5 Steps)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..5) {
                val isSelected = i == currentStep
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isSelected) 24.dp else 8.dp,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
                    label = "width"
                )
                val indicatorColor by animateColorAsState(
                    targetValue = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.15f),
                    animationSpec = androidx.compose.animation.core.tween(300),
                    label = "color"
                )
                Box(
                    modifier = Modifier
                        .size(width = indicatorWidth, height = 8.dp)
                        .clip(CircleShape)
                        .background(indicatorColor)
                )
                if (i < 5) Spacer(modifier = Modifier.width(6.dp))
            }
        }

        // Main Animated Content Box
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(contentScrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut())
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut())
                        }.using(
                            SizeTransform(clip = false)
                        )
                    },
                    label = "step_transition"
                ) { step ->
                    when (step) {
                        1 -> WelcomeStep(appLanguage, primaryColor, onSurfaceColor, onRestoreClick = { showRestoreDialog = true })
                        2 -> KeySafetyStep(appLanguage, primaryColor, onSurfaceColor)
                        3 -> PrivacyStep(appLanguage, primaryColor, onSurfaceColor)
                        4 -> RegisterStep(
                            nickname = nickname,
                            onNicknameChange = { nickname = it },
                            profileBitmap = profileBitmap,
                            onPickPhoto = { imagePickerLauncher.launch("image/*") },
                            appLanguage = appLanguage,
                            primaryColor = primaryColor,
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor
                        )
                        5 -> FinalizeStep(nickname, profileBitmap, fingerprint, appLanguage, primaryColor, surfaceColor, onSurfaceColor)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep > 1) {
                TextButton(
                    onClick = { currentStep-- },
                    colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
                ) {
                    Text(
                        text = Localizations.getString("back", appLanguage),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(80.dp))
            }

            Button(
                onClick = {
                    if (currentStep < 5) {
                        if (currentStep == 4) {
                            val normalizedNickname = normalizeProfileName(nickname)
                            if (normalizedNickname.isEmpty()) {
                                return@Button
                            }
                            nickname = normalizedNickname
                            sharedPrefs.edit()
                                .putString("username_profile", normalizedNickname)
                                .apply()
                        }
                        currentStep++
                    } else {
                        val normalizedNickname = normalizeProfileName(nickname)
                        if (normalizedNickname.isEmpty()) return@Button
                        nickname = normalizedNickname
                        sharedPrefs.edit()
                            .putString("username_profile", normalizedNickname)
                            .apply()
                        showYggdrasilDialog = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .height(56.dp)
                    .width(160.dp)
            ) {
                Text(
                    text = if (currentStep == 5) {
                        Localizations.getString("enter", appLanguage)
                    } else {
                        Localizations.getString("continue", appLanguage)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun WelcomeStep(
    appLanguage: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    onRestoreClick: () -> Unit = {}
) {
    val animationsEnabled = com.example.twopchat.LocalAppAnimationsEnabled.current
    val infiniteTransition = if (animationsEnabled) rememberInfiniteTransition(label = "pulse") else null
    val pulseScale = infiniteTransition?.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )?.value ?: 1.0f
    val glowAlpha = infiniteTransition?.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )?.value ?: 0.12f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glowing Logo Container
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_logo_default_fg),
                contentDescription = "2PChat Logo",
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(primaryColor)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.06f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(0.5.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = Localizations.getString("welcome_title", appLanguage),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = Localizations.getString("welcome_desc", appLanguage),
                    fontSize = 14.sp,
                    color = onSurfaceColor.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onRestoreClick,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.4f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .defaultMinSize(minHeight = 48.dp)
        ) {
            Text(
                text = "🔑 " + Localizations.getString("restore_account_btn", appLanguage),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
fun RegisterStep(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    profileBitmap: Bitmap?,
    onPickPhoto: () -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = Localizations.getString("create_profile", appLanguage),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = Localizations.getString("profile_desc", appLanguage),
            fontSize = 13.sp,
            color = onSurfaceColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Profile Photo Upload Circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.1f))
                .border(1.5.dp, primaryColor, CircleShape)
                .clickable { onPickPhoto() }
        ) {
            if (profileBitmap != null) {
                Image(
                    bitmap = profileBitmap.asImageBitmap(),
                    contentDescription = "Selected Profile Photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_add_photo_smiley),
                    contentDescription = "Add Photo",
                    tint = primaryColor,
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center)
                )
                Text(
                    text = Localizations.getString("add_photo", appLanguage),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        TextField(
            value = nickname,
            onValueChange = onNicknameChange,
            placeholder = { Text(Localizations.getString("placeholder_username", appLanguage), color = onSurfaceColor.copy(alpha = 0.4f)) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = surfaceColor,
                unfocusedContainerColor = surfaceColor,
                focusedTextColor = onSurfaceColor,
                unfocusedTextColor = onSurfaceColor,
                focusedIndicatorColor = primaryColor,
                unfocusedIndicatorColor = onSurfaceColor.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(1.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun VerifyStep(
    fingerprint: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = Localizations.getString("keys_generated", appLanguage),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = Localizations.getString("keys_desc", appLanguage),
            fontSize = 14.sp,
            color = onSurfaceColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Fingerprint Display card
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = fingerprint,
                    fontSize = 15.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = Localizations.getString("crypto_active", appLanguage),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun KeySafetyStep(appLanguage: String, primaryColor: Color, onSurfaceColor: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.12f))
                .border(1.5.dp, primaryColor, CircleShape)
        ) {
            Text(
                text = "🔑",
                fontSize = 48.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.06f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(0.5.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = Localizations.getString("step2_title", appLanguage),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = Localizations.getString("step2_desc", appLanguage),
                    fontSize = 14.sp,
                    color = onSurfaceColor.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )
            }
        }
    }
}

@Composable
fun PrivacyStep(appLanguage: String, primaryColor: Color, onSurfaceColor: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.12f))
                .border(1.5.dp, primaryColor, CircleShape)
        ) {
            Text(
                text = "🛡️",
                fontSize = 48.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.06f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(0.5.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = Localizations.getString("step3_title", appLanguage),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = Localizations.getString("step3_desc", appLanguage),
                    fontSize = 14.sp,
                    color = onSurfaceColor.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )
            }
        }
    }
}

@Composable
fun FinalizeStep(
    nickname: String,
    profileBitmap: Bitmap?,
    fingerprint: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color
) {
    var animateIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animateIn = true }
    val avatarScale by animateFloatAsState(
        targetValue = if (animateIn) 1.0f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "finalizeScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = avatarScale
                scaleY = avatarScale
            }
        ) {
            if (profileBitmap != null) {
                Image(
                    bitmap = profileBitmap.asImageBitmap(),
                    contentDescription = "Profile Photo",
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .border(2.dp, primaryColor, CircleShape)
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = 0.1f))
                        .border(2.dp, primaryColor, CircleShape)
                ) {
                    Text(
                        text = "🌐",
                        fontSize = 40.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = String.format(Localizations.getString("welcome_aboard", appLanguage), nickname.ifEmpty { "User" }),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = Localizations.getString("step5_desc", appLanguage),
            fontSize = 14.sp,
            color = onSurfaceColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = fingerprint,
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = Localizations.getString("crypto_active", appLanguage),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// Global helper to load bitmaps securely
fun loadBitmapFromUri(
    context: android.content.Context,
    uriString: String?,
    maxDimension: Int = 512,
): Bitmap? {
    if (uriString.isNullOrBlank()) return null
    val effectiveUri = uriString

    val targetDimension = maxDimension.coerceAtLeast(1)

    fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > targetDimension ||
            height / sample > targetDimension
        ) {
            sample *= 2
        }
        return sample
    }

    // 1. Direct file path check
    val rawPath = if (effectiveUri.startsWith("file://")) effectiveUri.removePrefix("file://") else effectiveUri
    val directFile = java.io.File(rawPath)
    if (directFile.exists()) {
        val fileBitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(directFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            BitmapFactory.decodeFile(
                directFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                },
            )
        }.getOrNull()
        if (fileBitmap != null) return fileBitmap
    }

    // 2. ContentResolver check
    val uri = Uri.parse(effectiveUri)
    val contentBitmap = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
    if (contentBitmap != null) return contentBitmap

    return null
}

fun saveImageToInternalStorage(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = java.io.File(context.filesDir, "profile_avatar.jpg")
        inputStream.use { input ->
            java.io.FileOutputStream(file).use { output ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}

enum class RestoreMode {
    PHRASE,
    BACKUP_FILE
}

enum class ChecksumStatus {
    INCOMPLETE,
    VALID,
    INVALID
}

@Composable
fun AccountRestoreModal(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    sharedPrefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        Bip39Dictionary.ensureLoaded(context)
    }

    var restoreMode by remember { mutableStateOf(RestoreMode.PHRASE) }
    val restoreTracker = remember { RestoreAttemptTracker(sharedPrefs) }
    var remainingLockoutMs by remember { mutableLongStateOf(restoreTracker.getRemainingLockoutMs()) }

    LaunchedEffect(remainingLockoutMs) {
        if (remainingLockoutMs > 0L) {
            kotlinx.coroutines.delay(500)
            remainingLockoutMs = restoreTracker.getRemainingLockoutMs()
        }
    }

    // --- Phrase Mode State ---
    var restoreNick by remember { mutableStateOf("") }
    val words = remember { mutableStateListOf(*Array(24) { "" }) }
    var rawPhraseInput by remember { mutableStateOf("") }
    var isQuickPasteMode by remember { mutableStateOf(false) }
    var focusedWordIndex by remember { mutableIntStateOf(0) }
    var checksumStatus by remember { mutableStateOf(ChecksumStatus.INCOMPLETE) }
    var isRestoringPhrase by remember { mutableStateOf(false) }
    var phraseError by remember { mutableStateOf<String?>(null) }
    var checksumDebounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun scheduleChecksumValidation() {
        checksumDebounceJob?.cancel()
        checksumDebounceJob = coroutineScope.launch {
            kotlinx.coroutines.delay(300)
            val filled = words.count { it.isNotBlank() }
            if (filled == 24) {
                val cleanWords = words.map { it.trim().lowercase() }
                val valid = Bip39Dictionary.validateChecksum(cleanWords)
                checksumStatus = if (valid) ChecksumStatus.VALID else ChecksumStatus.INVALID
            } else {
                checksumStatus = ChecksumStatus.INCOMPLETE
            }
        }
    }

    fun applyParsedWords(parsed: List<String>) {
        if (parsed.isNotEmpty()) {
            for (i in 0 until 24) {
                words[i] = if (i < parsed.size) parsed[i].lowercase() else ""
            }
            rawPhraseInput = words.filter { it.isNotBlank() }.joinToString(" ")
            scheduleChecksumValidation()
        }
    }

    // --- Backup File Mode State ---
    var selectedBackupUri by remember { mutableStateOf<Uri?>(null) }
    var backupFileName by remember { mutableStateOf<String?>(null) }
    var backupFormat by remember { mutableStateOf<ProfileBackupManager.BackupFormat?>(null) }
    var backupFingerprint by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isImportingFile by remember { mutableStateOf(false) }
    var fileImportError by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedBackupUri = uri
            fileImportError = null
            backupFileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            } ?: uri.lastPathSegment ?: "backup.2pbackup"

            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val inspected = ProfileBackupManager.inspectBackup(stream)
                    backupFormat = inspected.format
                    backupFingerprint = inspected.fingerprint
                }
            } catch (e: Exception) {
                fileImportError = e.message ?: "Failed to read backup file"
            }
        }
    }

    val isBusy = isRestoringPhrase || isImportingFile
    val isLockedOut = remainingLockoutMs > 0L

    val canConfirm = !isBusy && !isLockedOut && when (restoreMode) {
        RestoreMode.PHRASE -> checksumStatus == ChecksumStatus.VALID
        RestoreMode.BACKUP_FILE -> selectedBackupUri != null && (
            backupFormat == ProfileBackupManager.BackupFormat.V1_PLAINTEXT_ZIP ||
            (backupFormat == ProfileBackupManager.BackupFormat.V2_ENCRYPTED && backupPassword.length >= 6)
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔑", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = Localizations.getString("restore_account_title", appLanguage),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = onSurfaceColor
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode Selector (Phrase vs Backup File)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (restoreMode == RestoreMode.PHRASE) primaryColor.copy(alpha = 0.2f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (restoreMode == RestoreMode.PHRASE) primaryColor else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { restoreMode = RestoreMode.PHRASE }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔤 " + Localizations.getString("restore_mode_phrase", appLanguage),
                            fontSize = 12.sp,
                            fontWeight = if (restoreMode == RestoreMode.PHRASE) FontWeight.Bold else FontWeight.Normal,
                            color = if (restoreMode == RestoreMode.PHRASE) primaryColor else onSurfaceColor.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (restoreMode == RestoreMode.BACKUP_FILE) primaryColor.copy(alpha = 0.2f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (restoreMode == RestoreMode.BACKUP_FILE) primaryColor else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { restoreMode = RestoreMode.BACKUP_FILE }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "💾 " + Localizations.getString("restore_mode_file", appLanguage),
                            fontSize = 12.sp,
                            fontWeight = if (restoreMode == RestoreMode.BACKUP_FILE) FontWeight.Bold else FontWeight.Normal,
                            color = if (restoreMode == RestoreMode.BACKUP_FILE) primaryColor else onSurfaceColor.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }

                // Informational Description Card
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.07f)),
                    border = BorderStroke(0.5.dp, primaryColor.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (restoreMode == RestoreMode.PHRASE) {
                            Localizations.getString("restore_mode_phrase_desc", appLanguage)
                        } else {
                            Localizations.getString("restore_mode_file_desc", appLanguage)
                        },
                        fontSize = 12.sp,
                        color = onSurfaceColor.copy(alpha = 0.85f),
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                // Lockout Banner
                if (isLockedOut) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, Color(0xFFEF5350)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = String.format(
                                Localizations.getString("restore_lockout_active", appLanguage),
                                (remainingLockoutMs / 1000L) + 1
                            ),
                            color = Color(0xFFEF5350),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        )
                    }
                }

                // Mode-specific content
                if (restoreMode == RestoreMode.PHRASE) {
                    // Nickname field
                    OutlinedTextField(
                        value = restoreNick,
                        onValueChange = { restoreNick = it; phraseError = null },
                        label = { Text(Localizations.getString("placeholder_username", appLanguage)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            focusedLabelColor = primaryColor
                        )
                    )

                    // Switch between Grid view and Quick Paste
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { isQuickPasteMode = !isQuickPasteMode }
                        ) {
                            Text(
                                text = if (isQuickPasteMode) "⌨️ Switch to 24-Word Grid" else "📝 Quick Paste (All Words)",
                                color = primaryColor,
                                fontSize = 12.sp
                            )
                        }

                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                if (!clipText.isNullOrBlank()) {
                                    val parsed = Bip39Dictionary.parseMnemonicWords(clipText)
                                    if (parsed.isNotEmpty()) {
                                        applyParsedWords(parsed)
                                        phraseError = null
                                        android.widget.Toast.makeText(
                                            context,
                                            Localizations.getString("restore_paste_split_success", appLanguage),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        phraseError = Localizations.getString("restore_paste_split_fail", appLanguage)
                                    }
                                }
                            }
                        ) {
                            Text("📋 " + Localizations.getString("paste_from_clipboard", appLanguage), color = primaryColor, fontSize = 12.sp)
                        }
                    }

                    if (isQuickPasteMode) {
                        OutlinedTextField(
                            value = rawPhraseInput,
                            onValueChange = { input ->
                                rawPhraseInput = input
                                phraseError = null
                                val parsed = Bip39Dictionary.parseMnemonicWords(input)
                                applyParsedWords(parsed)
                            },
                            label = { Text(Localizations.getString("seed_backup_dialog_title", appLanguage)) },
                            placeholder = { Text(Localizations.getString("enter_seed_placeholder", appLanguage), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                focusedLabelColor = primaryColor
                            )
                        )
                    } else {
                        // Word suggestions row for focused word
                        val currentWord = words.getOrNull(focusedWordIndex).orEmpty().trim().lowercase()
                        val suggestions = remember(currentWord) {
                            if (currentWord.length >= 2 && !Bip39Dictionary.isValidWord(currentWord)) {
                                Bip39Dictionary.suggestWords(currentWord, limit = 4)
                            } else {
                                emptyList()
                            }
                        }

                        if (suggestions.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(suggestions) { suggestion ->
                                    SuggestionChip(
                                        onClick = {
                                            words[focusedWordIndex] = suggestion
                                            if (focusedWordIndex < 23) {
                                                focusedWordIndex++
                                            }
                                            scheduleChecksumValidation()
                                        },
                                        label = { Text(suggestion, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = primaryColor.copy(alpha = 0.15f),
                                            labelColor = primaryColor
                                        ),
                                        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.3f))
                                    )
                                }
                            }
                        }

                        // 8 rows x 3 columns grid
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (row in 0 until 8) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for (col in 0 until 3) {
                                        val index = row * 3 + col
                                        val word = words[index]
                                        val isValid = Bip39Dictionary.isValidWord(word)

                                        OutlinedTextField(
                                            value = word,
                                            onValueChange = { newText ->
                                                words[index] = newText.trim().lowercase()
                                                focusedWordIndex = index
                                                phraseError = null
                                                scheduleChecksumValidation()
                                            },
                                            label = { Text("${index + 1}", fontSize = 9.sp) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = if (isValid) Color(0xFF4CAF50) else if (word.isNotBlank()) Color(0xFFEF5350) else primaryColor,
                                                unfocusedBorderColor = if (word.isBlank()) onSurfaceColor.copy(alpha = 0.2f) else if (isValid) Color(0xFF4CAF50) else Color(0xFFEF5350)
                                            ),
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Checksum Status Badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when (checksumStatus) {
                                    ChecksumStatus.VALID -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                                    ChecksumStatus.INVALID -> Color(0xFFB71C1C).copy(alpha = 0.2f)
                                    ChecksumStatus.INCOMPLETE -> surfaceColor.copy(alpha = 0.5f)
                                }
                            )
                            .border(
                                1.dp,
                                when (checksumStatus) {
                                    ChecksumStatus.VALID -> Color(0xFF4CAF50)
                                    ChecksumStatus.INVALID -> Color(0xFFEF5350)
                                    ChecksumStatus.INCOMPLETE -> onSurfaceColor.copy(alpha = 0.15f)
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when (checksumStatus) {
                                ChecksumStatus.VALID -> Localizations.getString("restore_checksum_valid", appLanguage)
                                ChecksumStatus.INVALID -> Localizations.getString("restore_checksum_invalid", appLanguage)
                                ChecksumStatus.INCOMPLETE -> String.format(
                                    Localizations.getString("restore_checksum_incomplete", appLanguage),
                                    words.count { it.isNotBlank() }
                                )
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when (checksumStatus) {
                                ChecksumStatus.VALID -> Color(0xFF4CAF50)
                                ChecksumStatus.INVALID -> Color(0xFFEF5350)
                                ChecksumStatus.INCOMPLETE -> onSurfaceColor.copy(alpha = 0.7f)
                            }
                        )
                    }

                    val err = phraseError
                    if (err != null) {
                        Text(
                            text = err,
                            color = Color(0xFFEF5350),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    // --- Backup File UI ---
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor),
                        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 " + Localizations.getString("restore_select_file", appLanguage), fontWeight = FontWeight.SemiBold)
                    }

                    if (selectedBackupUri != null) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.7f)),
                            border = BorderStroke(0.5.dp, primaryColor.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = String.format(Localizations.getString("restore_file_selected", appLanguage), backupFileName.orEmpty()),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = onSurfaceColor
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                when (backupFormat) {
                                    ProfileBackupManager.BackupFormat.V2_ENCRYPTED -> {
                                        Text(
                                            text = "🔒 Encrypted Backup v2 (2PBK)",
                                            color = Color(0xFF4CAF50),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (!backupFingerprint.isNullOrBlank()) {
                                            Text(
                                                text = "Identity: ${backupFingerprint?.take(16)}...",
                                                fontSize = 11.sp,
                                                color = onSurfaceColor.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    ProfileBackupManager.BackupFormat.V1_PLAINTEXT_ZIP -> {
                                        Text(
                                            text = "⚠ Legacy Unencrypted Archive (ZIP)",
                                            color = Color(0xFFFFA000),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "❓ Unknown / Damaged file",
                                            color = Color(0xFFEF5350),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        if (backupFormat == ProfileBackupManager.BackupFormat.V2_ENCRYPTED) {
                            OutlinedTextField(
                                value = backupPassword,
                                onValueChange = { backupPassword = it; fileImportError = null },
                                label = { Text(Localizations.getString("restore_enter_password", appLanguage)) },
                                singleLine = true,
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Text(if (isPasswordVisible) "👁" else "🙈")
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = primaryColor,
                                    focusedLabelColor = primaryColor
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    val fileErr = fileImportError
                    if (fileErr != null) {
                        Text(
                            text = fileErr,
                            color = Color(0xFFEF5350),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (restoreMode == RestoreMode.PHRASE) {
                        val trimmedNick = restoreNick.trim().ifEmpty { "User" }
                        val phrase = words.joinToString(" ") { it.trim().lowercase() }
                        isRestoringPhrase = true
                        phraseError = null
                        coroutineScope.launch {
                            val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                NativeBridge.restoreFromMnemonic(trimmedNick, phrase, "")
                            }
                            if (success) {
                                restoreTracker.recordSuccess()
                                sharedPrefs.edit()
                                    .putString("username_profile", trimmedNick)
                                    .putBoolean("onboarding_completed", true) // FIXED TYPO
                                    .apply()
                                android.widget.Toast.makeText(
                                    context,
                                    Localizations.getString("account_restored_success", appLanguage),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onSuccess()
                            } else {
                                restoreTracker.recordFailure()
                                remainingLockoutMs = restoreTracker.getRemainingLockoutMs()
                                phraseError = Localizations.getString("invalid_seed_error", appLanguage)
                                isRestoringPhrase = false
                            }
                        }
                    } else {
                        val uri = selectedBackupUri ?: return@Button
                        isImportingFile = true
                        fileImportError = null
                        coroutineScope.launch {
                            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        ProfileBackupManager.importBackup(
                                            context,
                                            backupPassword,
                                            stream,
                                            allowForeignFingerprint = true
                                        )
                                    }
                                } catch (e: Exception) {
                                    ProfileBackupManager.BackupImportResult(success = false, errorMessage = e.message)
                                }
                            }
                            if (res != null && res.success) {
                                restoreTracker.recordSuccess()
                                sharedPrefs.edit()
                                    .putBoolean("onboarding_completed", true) // FIXED TYPO
                                    .apply()
                                if (!res.restoredNickname.isNullOrBlank()) {
                                    sharedPrefs.edit().putString("username_profile", res.restoredNickname).apply()
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    Localizations.getString("account_restored_success", appLanguage),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onSuccess()
                            } else {
                                restoreTracker.recordFailure()
                                remainingLockoutMs = restoreTracker.getRemainingLockoutMs()
                                fileImportError = res?.errorMessage ?: "Failed to restore backup"
                                isImportingFile = false
                            }
                        }
                    }
                },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = if (primaryColor == MintGreen) StealthBlack else Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Localizations.getString("restore_in_progress", appLanguage), fontWeight = FontWeight.Bold)
                } else {
                    Text(Localizations.getString("restore_btn", appLanguage), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy
            ) {
                Text(Localizations.getString("close", appLanguage), color = onSurfaceColor.copy(alpha = 0.6f))
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp)
    )
}
