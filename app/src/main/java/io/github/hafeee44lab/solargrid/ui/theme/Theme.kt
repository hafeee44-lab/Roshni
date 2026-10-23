package io.github.hafeee44lab.solargrid.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.ThemeMode

/**
 * Energy is colour-coded the same way everywhere — flow diagram, charts, notifications:
 * solar is amber, grid is sky, battery is violet, home is ink. Green means "fine", coral
 * means "the mains are gone". Nothing else gets to use those two.
 */
@Immutable
data class SolarColors(
    val isDark: Boolean,
    val bg: Color,
    val bgDeep: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val solar: Color,
    val solarDeep: Color,
    val grid: Color,
    val battery: Color,
    val home: Color,
    val ok: Color,
    val down: Color,
    val warn: Color,
    val track: Color,
    val onAccent: Color
)

private val Dark = SolarColors(
    isDark = true,
    bg = Color(0xFF070A10),
    bgDeep = Color(0xFF04060A),
    surface = Color(0xFF0F141D),
    surfaceHigh = Color(0xFF161D29),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x26FFFFFF),
    ink = Color(0xFFEEF2F8),
    ink2 = Color(0xFFA6B0C0),
    ink3 = Color(0xFF8590A2),
    solar = Color(0xFFFFB23F),
    solarDeep = Color(0xFFFF7A2F),
    grid = Color(0xFF4CC9FF),
    battery = Color(0xFF9B8CFF),
    home = Color(0xFFEEF2F8),
    ok = Color(0xFF2BD99F),
    down = Color(0xFFFF4D6A),
    warn = Color(0xFFFFC547),
    track = Color(0x1AFFFFFF),
    onAccent = Color(0xFF06080C)
)

private val Light = SolarColors(
    isDark = false,
    bg = Color(0xFFF5F2ED),
    bgDeep = Color(0xFFECE7E0),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF7F5F2),
    hairline = Color(0x12000000),
    hairlineStrong = Color(0x24000000),
    ink = Color(0xFF12161D),
    ink2 = Color(0xFF4A5262),
    ink3 = Color(0xFF626A79),
    solar = Color(0xFFA86400),
    solarDeep = Color(0xFFC2410C),
    grid = Color(0xFF0675AD),
    battery = Color(0xFF6A55F2),
    home = Color(0xFF12161D),
    ok = Color(0xFF08875D),
    down = Color(0xFFD9203F),
    warn = Color(0xFF93600F),
    track = Color(0x14000000),
    onAccent = Color(0xFFFFFFFF)
)

val LocalSolarColors = staticCompositionLocalOf { Dark }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Solar {
    val colors: SolarColors
        @Composable @ReadOnlyComposable get() = LocalSolarColors.current

    /** Honour "Remove animations": everything decorative stops, state changes still crossfade. */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val Grotesk = FontFamily(
    grotesk(300),
    grotesk(400),
    grotesk(500),
    grotesk(600),
    grotesk(700)
)

/** Type scale. Numbers use tabular figures so a ticking value never jitters sideways. */
object SolarType {
    val display = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp)
    val hero = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.9).sp)
    val title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp)
    val number = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum")
    val numberSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")
    val label = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val bodyStrong = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun SolarTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) Dark else Light

    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.ok,
            onPrimary = colors.onAccent,
            secondary = colors.battery,
            background = colors.bg,
            surface = colors.surface,
            surfaceContainer = colors.surfaceHigh,
            surfaceContainerHigh = colors.surfaceHigh,
            surfaceContainerHighest = colors.surfaceHigh,
            onBackground = colors.ink,
            onSurface = colors.ink,
            onSurfaceVariant = colors.ink2,
            outline = colors.hairlineStrong,
            outlineVariant = colors.hairline,
            error = colors.down
        )
    } else {
        lightColorScheme(
            primary = colors.ok,
            onPrimary = colors.onAccent,
            secondary = colors.battery,
            background = colors.bg,
            surface = colors.surface,
            surfaceContainer = colors.surfaceHigh,
            surfaceContainerHigh = colors.surfaceHigh,
            surfaceContainerHighest = colors.surfaceHigh,
            onBackground = colors.ink,
            onSurface = colors.ink,
            onSurfaceVariant = colors.ink2,
            outline = colors.hairlineStrong,
            outlineVariant = colors.hairline,
            error = colors.down
        )
    }

    CompositionLocalProvider(
        LocalSolarColors provides colors,
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                bodyLarge = SolarType.body.copy(fontSize = 15.sp),
                bodyMedium = SolarType.body,
                labelLarge = SolarType.bodyStrong,
                titleLarge = SolarType.title
            ),
            content = content
        )
    }
}
