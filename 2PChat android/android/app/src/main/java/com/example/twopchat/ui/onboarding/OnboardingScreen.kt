package com.example.twopchat.ui.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.example.twopchat.PythonBridge
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations

@Composable
fun OnboardingScreen(
    appLanguage: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { com.example.twopchat.P2PPreferences.prefs(context) }
    
    var currentStep by remember { mutableStateOf(1) }
    var nickname by remember { mutableStateOf("") }
    var profilePhotoUri by remember { mutableStateOf(sharedPrefs.getString("profile_photo_uri", null)) }
    var profileBitmap by remember { mutableStateOf<Bitmap?>(loadBitmapFromUri(context, profilePhotoUri)) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }

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
                profileBitmap = loadBitmapFromUri(context, localPath)
                pendingCropUri = null
            },
            onCancel = {
                pendingCropUri = null
            },
            appLanguage = appLanguage
        )
        return
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
        // Step Indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..4) {
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
                if (i < 4) Spacer(modifier = Modifier.width(6.dp))
            }
        }

        // Main Animated Content Box (scrollable to prevent keyboard overlap)
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
                        2 -> RegisterStep(
                            nickname = nickname,
                            onNicknameChange = { nickname = it },
                            profileBitmap = profileBitmap,
                            onPickPhoto = { imagePickerLauncher.launch("image/*") },
                            appLanguage = appLanguage,
                            primaryColor = primaryColor,
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor
                        )
                        3 -> VerifyStep(fingerprint, appLanguage, primaryColor, surfaceColor, onSurfaceColor)
                        4 -> FinalizeStep(nickname, profileBitmap, appLanguage, primaryColor, onSurfaceColor)
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
                    if (currentStep < 4) {
                        if (currentStep == 2) {
                            val normalizedNickname = normalizeProfileName(nickname)
                            if (normalizedNickname.isEmpty()) {
                                // Validation: nickname required
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
                        onComplete()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .height(56.dp)
                    .width(160.dp) // Increased width to fit Russian 'Продолжить' without wrapping
            ) {
                Text(
                    text = if (currentStep == 4) {
                        Localizations.getString("enter", appLanguage)
                    } else {
                        Localizations.getString("continue", appLanguage)
                    },
                    fontSize = 14.sp, // Clean premium font sizing
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

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = Localizations.getString("welcome_title", appLanguage),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = Localizations.getString("welcome_desc", appLanguage),
            fontSize = 15.sp,
            color = onSurfaceColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
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
fun FinalizeStep(
    nickname: String,
    profileBitmap: Bitmap?,
    appLanguage: String,
    primaryColor: Color,
    onSurfaceColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Selected Profile picture displaying at success page
        if (profileBitmap != null) {
            Image(
                bitmap = profileBitmap.asImageBitmap(),
                contentDescription = "Profile Photo",
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .border(2.dp, primaryColor, CircleShape)
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.1f))
                    .border(2.dp, primaryColor, CircleShape)
            ) {
                Text(
                    text = "✓",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = primaryColor
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = String.format(Localizations.getString("welcome_aboard", appLanguage), nickname),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = Localizations.getString("finalize_desc", appLanguage),
            fontSize = 14.sp,
            color = onSurfaceColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

// Global helper to load bitmaps securely
fun loadBitmapFromUri(context: android.content.Context, uriString: String?): Bitmap? {
    if (uriString.isNullOrEmpty()) return null
    return try {
        val uri = Uri.parse(uriString)
        val inputStream = context.contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(inputStream)
    } catch (e: Exception) {
        try {
            BitmapFactory.decodeFile(uriString)
        } catch (ex: Exception) {
            null
        }
    }
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
