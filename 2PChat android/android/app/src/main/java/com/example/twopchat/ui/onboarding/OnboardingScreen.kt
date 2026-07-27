package com.example.twopchat.ui.onboarding

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.twopchat.P2PPreferences
import com.example.twopchat.PythonBridge
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations

@Composable
fun OnboardingScreen(
    appLanguage: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { P2PPreferences.prefs(context) }
    
    var currentStep by remember { mutableStateOf(1) }
    var nickname by remember { mutableStateOf("") }
    var profilePhotoUri by remember { mutableStateOf(sharedPrefs.getString("profile_photo_uri", null)) }
    var profileBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var showYggdrasilDialog by remember { mutableStateOf(false) }

    fun startYggdrasilAndComplete() {
        try {
            sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
            ContextCompat.startForegroundService(
                context,
                Intent(context, PacketTunnelProvider::class.java).apply {
                    action = PacketTunnelProvider.ACTION_START
                },
            )
            onComplete()
        } catch (error: RuntimeException) {
            android.util.Log.e("OnboardingScreen", "Unable to start Yggdrasil VPN", error)
            sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
            showYggdrasilDialog = true
        }
    }

    LaunchedEffect(profilePhotoUri) {
        profileBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadBitmapFromUri(context, profilePhotoUri)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) {
            startYggdrasilAndComplete()
        } else {
            sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
            showYggdrasilDialog = true
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
        while (!PythonBridge.isInitialized) {
            kotlinx.coroutines.delay(100)
        }
        fingerprint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PythonBridge.getLocalFingerprint()
        }
    }
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    if (pendingCropUri != null) {
        ImageCropper(
            imageUri = pendingCropUri!!,
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
            onDismissRequest = { showYggdrasilDialog = false },
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
                        val vpnPrepareIntent = VpnService.prepare(context)
                        if (vpnPrepareIntent != null) {
                            sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
                            vpnPermissionLauncher.launch(vpnPrepareIntent)
                        } else {
                            startYggdrasilAndComplete()
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
                        sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
                        onComplete()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Step Indicators (5 Steps)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
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
                .verticalScroll(rememberScrollState()),
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
                        1 -> WelcomeStep(appLanguage, primaryColor, onSurfaceColor)
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

        // Action Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
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
fun WelcomeStep(appLanguage: String, primaryColor: Color, onSurfaceColor: Color) {
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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = Localizations.getString("create_profile", appLanguage),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = Localizations.getString("profile_desc", appLanguage),
            fontSize = 14.sp,
            color = onSurfaceColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Profile Photo Upload Circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
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
                        .size(48.dp)
                        .align(Alignment.Center)
                )
                Text(
                    text = Localizations.getString("add_photo", appLanguage),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

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
                .padding(horizontal = 16.dp)
                .border(1.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
        )
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
    if (uriString.isNullOrEmpty()) return null
    val uri = Uri.parse(uriString)
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

    return runCatching {
        val filePath = uri.path.takeUnless { uri.scheme.isNullOrBlank() } ?: uriString
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        BitmapFactory.decodeFile(
            filePath,
            BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            },
        )
    }.getOrNull()
}

fun saveImageToInternalStorage(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = java.io.File(context.filesDir, "profile_avatar.jpg")
        val outputStream = java.io.FileOutputStream(file)
        val buffer = ByteArray(4 * 1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
        outputStream.flush()
        outputStream.close()
        inputStream.close()
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}
