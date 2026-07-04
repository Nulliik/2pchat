package com.example.twopchat.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkMintColorScheme = darkColorScheme(
    primary = MintGreen,
    secondary = DeepPine,
    background = StealthBlack,
    surface = Onyx,
    onPrimary = StealthBlack,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Onyx,
    onSurfaceVariant = TextGray
)

private val DarkBlueColorScheme = darkColorScheme(
    primary = CeruleanBlue,
    secondary = DeepCerulean,
    background = StealthBlack,
    surface = Onyx,
    onPrimary = StealthBlack,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Onyx,
    onSurfaceVariant = TextGray
)

private val LightMintColorScheme = lightColorScheme(
    primary = MintGreenLight,
    secondary = DeepPine,
    background = AlabasterCream,
    surface = PremiumWhite,
    onPrimary = Color.White,
    onBackground = TextDark,
    onSurface = TextDark,
    surfaceVariant = CardLight,
    onSurfaceVariant = TextSubdued
)

private val LightBlueColorScheme = lightColorScheme(
    primary = CeruleanBlueLight,
    secondary = DeepCerulean,
    background = AlabasterCream,
    surface = PremiumWhite,
    onPrimary = Color.White,
    onBackground = TextDark,
    onSurface = TextDark,
    surfaceVariant = CardLight,
    onSurfaceVariant = TextSubdued
)

@Composable
fun _2PChatTheme(
  darkTheme: Boolean = true,
  useCerulean: Boolean = false,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> {
        if (useCerulean) DarkBlueColorScheme else DarkMintColorScheme
      }
      else -> {
        if (useCerulean) LightBlueColorScheme else LightMintColorScheme
      }
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
