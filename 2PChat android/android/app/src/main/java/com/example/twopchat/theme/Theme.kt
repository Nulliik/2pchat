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
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C212A)
)

private val DarkBlueColorScheme = darkColorScheme(
    primary = CeruleanBlue,
    secondary = DeepCerulean,
    background = DarkBgBlue,
    surface = DarkSurfaceBlue,
    onPrimary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = DarkSurfaceVariantBlue,
    onSurfaceVariant = TextGray,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C212A)
)

private val DarkPurpleColorScheme = darkColorScheme(
    primary = AmethystPurple,
    secondary = DeepPurple,
    background = DarkBgBlue,
    surface = DarkSurfaceBlue,
    onPrimary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF1E1728),
    onSurfaceVariant = TextGray,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C212A)
)

private val DarkAmberColorScheme = darkColorScheme(
    primary = SolarAmber,
    secondary = DeepAmber,
    background = DarkBgBlue,
    surface = DarkSurfaceBlue,
    onPrimary = Color.Black,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF231C14),
    onSurfaceVariant = TextGray,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C212A)
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
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF111417),
    surfaceContainerHigh = Color(0xFF1A1F24)
)

private val AmoledBlueColorScheme = darkColorScheme(
    primary = CeruleanBlue,
    secondary = DeepCerulean,
    background = Color.Black,
    surface = StealthBlack,
    onPrimary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF151D2A),
    onSurfaceVariant = TextGray,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF111722),
    surfaceContainerHigh = Color(0xFF1A2233)
)

private val AmoledPurpleColorScheme = darkColorScheme(
    primary = AmethystPurple,
    secondary = DeepPurple,
    background = Color.Black,
    surface = StealthBlack,
    onPrimary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF1B1324),
    onSurfaceVariant = TextGray,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF111417),
    surfaceContainerHigh = Color(0xFF1A1F24)
)

private val AmoledAmberColorScheme = darkColorScheme(
    primary = SolarAmber,
    secondary = DeepAmber,
    background = Color.Black,
    surface = StealthBlack,
    onPrimary = Color.Black,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF22170B),
    onSurfaceVariant = TextGray,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0DFFFFFF),
    surfaceContainer = Color(0xFF111417),
    surfaceContainerHigh = Color(0xFF1A1F24)
)

private val LightMintColorScheme = lightColorScheme(
    primary            = MintGreenLight,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFB8F0DC),
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
    outlineVariant     = Color(0x0A0F172A)
)

private val LightBlueColorScheme = lightColorScheme(
    primary            = CeruleanBlueLight,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFCCE4FF),
    onPrimaryContainer = Color(0xFF001F4A),
    secondary          = DeepCerulean,
    background         = Color(0xFFF0F4F8),
    onBackground       = TextDark,
    surface            = LightSurface,
    onSurface          = TextDark,
    surfaceVariant     = Color(0xFFE2E8F0),
    onSurfaceVariant   = TextSubdued,
    surfaceContainer   = Color(0xFFFFFFFF),
    outline            = BorderLight,
    outlineVariant     = Color(0x0A0F172A)
)

private val LightPurpleColorScheme = lightColorScheme(
    primary            = AmethystPurpleLight,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFF3E8FF),
    onPrimaryContainer = Color(0xFF3B0764),
    secondary          = DeepPurple,
    background         = Color(0xFFFAF8FD),
    onBackground       = TextDark,
    surface            = LightSurface,
    onSurface          = TextDark,
    surfaceVariant     = Color(0xFFF3F0F9),
    onSurfaceVariant   = TextSubdued,
    surfaceContainer   = Color(0xFFFFFFFF),
    outline            = BorderLight,
    outlineVariant     = Color(0x0A0F172A)
)

private val LightAmberColorScheme = lightColorScheme(
    primary            = SolarAmberLight,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    secondary          = DeepAmber,
    background         = Color(0xFFFAF9F5),
    onBackground       = TextDark,
    surface            = LightSurface,
    onSurface          = TextDark,
    surfaceVariant     = Color(0xFFF5F3ED),
    onSurfaceVariant   = TextSubdued,
    surfaceContainer   = Color(0xFFFFFFFF),
    outline            = BorderLight,
    outlineVariant     = Color(0x0A0F172A)
)

@Composable
fun _2PChatTheme(
  darkTheme: Boolean = true,
  useCerulean: Boolean = false,
  accentScheme: String = if (useCerulean) "cerulean" else "mint",
  useAmoled: Boolean = false,
  dynamicColor: Boolean = false,
  animationsEnabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val effectiveScheme = if (accentScheme.isNotBlank()) accentScheme else if (useCerulean) "cerulean" else "mint"
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> {
        if (useAmoled) {
          when (effectiveScheme) {
            "cerulean" -> AmoledBlueColorScheme
            "purple" -> AmoledPurpleColorScheme
            "amber" -> AmoledAmberColorScheme
            else -> AmoledMintColorScheme
          }
        } else {
          when (effectiveScheme) {
            "cerulean" -> DarkBlueColorScheme
            "purple" -> DarkPurpleColorScheme
            "amber" -> DarkAmberColorScheme
            else -> DarkMintColorScheme
          }
        }
      }
      else -> {
        when (effectiveScheme) {
          "cerulean" -> LightBlueColorScheme
          "purple" -> LightPurpleColorScheme
          "amber" -> LightAmberColorScheme
          else -> LightMintColorScheme
        }
      }
    }

  CompositionLocalProvider(LocalAppAnimationsEnabled provides animationsEnabled) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
