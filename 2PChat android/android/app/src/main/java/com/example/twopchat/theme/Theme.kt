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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.example.twopchat.LocalAppAnimationsEnabled

private val DarkMintColorScheme = darkColorScheme(
    primary = MintGreen,
    secondary = DeepPine,
    background = DarkBgMint,
    surface = DarkSurfaceMint,
    onPrimary = StealthBlack,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = DarkSurfaceVariantMint,
    onSurfaceVariant = TextGray,
    outline = Color(0xFF26332C),
    outlineVariant = Color(0xFF1B2620),
    surfaceContainer = Color(0xFF141F1A),
    surfaceContainerHigh = Color(0xFF1C2B24)
)

private val DarkBlueColorScheme = darkColorScheme(
    primary = CeruleanBlue,
    secondary = DeepCerulean,
    background = DarkBgBlue,
    surface = DarkSurfaceBlue,
    onPrimary = StealthBlack,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = DarkSurfaceVariantBlue,
    onSurfaceVariant = TextGray,
    outline = Color(0xFF222B3D),
    outlineVariant = Color(0xFF182030),
    surfaceContainer = Color(0xFF141926),
    surfaceContainerHigh = Color(0xFF1E2538)
)

private val AmoledMintColorScheme = darkColorScheme(
    primary = MintGreen,
    secondary = DeepPine,
    background = Color.Black,
    surface = StealthBlack,
    onPrimary = StealthBlack,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF161A1D),
    onSurfaceVariant = TextGray,
    outline = Color(0xFF22272B),
    outlineVariant = Color(0xFF191C1F),
    surfaceContainer = Color(0xFF111417),
    surfaceContainerHigh = Color(0xFF1A1F24)
)

private val AmoledBlueColorScheme = darkColorScheme(
    primary = CeruleanBlue,
    secondary = DeepCerulean,
    background = Color.Black,
    surface = StealthBlack,
    onPrimary = StealthBlack,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF151D2A),
    onSurfaceVariant = TextGray,
    outline = Color(0xFF202A3B),
    outlineVariant = Color(0xFF171F2C),
    surfaceContainer = Color(0xFF111722),
    surfaceContainerHigh = Color(0xFF1A2233)
)

private val LightMintColorScheme = lightColorScheme(
    primary            = MintGreenLight,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFB8F0DC),  // soft mint tint for chips/badges
    onPrimaryContainer = Color(0xFF00382A),
    secondary          = DeepPine,
    background         = AlabasterCream,
    onBackground       = TextDark,
    surface            = LightSurface,
    onSurface          = TextDark,
    surfaceVariant     = LightSurfaceVariant,
    onSurfaceVariant   = TextSubdued,
    surfaceContainer   = LightSurfaceContainer,
    outline            = BorderLight,
    outlineVariant     = Color(0xFFE4EAE4)
)

private val LightBlueColorScheme = lightColorScheme(
    primary            = CeruleanBlueLight,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFCCE4FF),
    onPrimaryContainer = Color(0xFF001F4A),
    secondary          = DeepCerulean,
    background         = Color(0xFFE6EBF2),  // soft blue-tinted grey background
    onBackground       = TextDark,
    surface            = LightSurface,
    onSurface          = TextDark,
    surfaceVariant     = Color(0xFFE8EDF5),
    onSurfaceVariant   = TextSubdued,
    surfaceContainer   = Color(0xFFEEF2F8),
    outline            = Color(0xFFD4DCE8),
    outlineVariant     = Color(0xFFDDE4EE)
)

@Composable
fun _2PChatTheme(
  darkTheme: Boolean = true,
  useCerulean: Boolean = false,
  useAmoled: Boolean = false,
  dynamicColor: Boolean = false,
  animationsEnabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> {
        if (useAmoled) {
          if (useCerulean) AmoledBlueColorScheme else AmoledMintColorScheme
        } else {
          if (useCerulean) DarkBlueColorScheme else DarkMintColorScheme
        }
      }
      else -> {
        if (useCerulean) LightBlueColorScheme else LightMintColorScheme
      }
    }

  CompositionLocalProvider(LocalAppAnimationsEnabled provides animationsEnabled) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
