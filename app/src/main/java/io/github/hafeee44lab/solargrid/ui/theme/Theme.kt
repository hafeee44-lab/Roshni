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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.hafeee44lab.solargrid.data.ThemeMode

/**
 * A quiet, conventional palette in the manner of SOLARMAN Smart: cool grey ground, white
 * cards, thin lines. Colour only ever means something — solar is amber, the grid is slate,
 * the battery takes the colour of its own level, and red is spent on outages alone.
 */
@Immutable
data class SolarColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val brand: Color,
    val brandContainer: Color,
    val onBrand: Color,
    val solar: Color,
    val solarText: Color,
    val grid: Color,
    val home: Color,
    val ok: Color,
    val okContainer: Color,
    val down: Color,
    val downContainer: Color,
    val onDownContainer: Color,
    val warn: Color,
    val batteryHigh: Color,
    val batteryMid: Color,
    val batteryLow: Color
) {
    /** The battery reads like a phone battery: green, then amber, then red. */
    fun battery(soc: Double?, lowPercent: Int = 20): Color = when {
        soc == null -> ink3
        soc <= lowPercent -> batteryLow
        soc < 50 -> batteryMid
        else -> batteryHigh
    }
}

private val Light = SolarColors(
    isDark = false,
    bg = Color(0xFFF2F4F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F4),
    outline = Color(0xFFE3E7ED),
    outlineStrong = Color(0xFFC9CFD8),
    ink = Color(0xFF16191F),
    ink2 = Color(0xFF4F5663),
    ink3 = Color(0xFF6A7180),
    brand = Color(0xFF2457C5),
    brandContainer = Color(0xFFE3EBFB),
    onBrand = Color(0xFFFFFFFF),
    solar = Color(0xFFF2A007),
    solarText = Color(0xFF9A5B00),
    grid = Color(0xFF5F6E86),
    home = Color(0xFF2457C5),
    ok = Color(0xFF1B8A4E),
    okContainer = Color(0xFFE2F4E9),
    down = Color(0xFFC62828),
    downContainer = Color(0xFFFCEBEA),
    onDownContainer = Color(0xFF7A1212),
    warn = Color(0xFF8F5A00),
    batteryHigh = Color(0xFF1B8A4E),
    batteryMid = Color(0xFFB7791F),
    batteryLow = Color(0xFFC62828)
)

private val Dark = SolarColors(
    isDark = true,
    bg = Color(0xFF111318),
    surface = Color(0xFF1B1F26),
    surfaceVariant = Color(0xFF262B34),
    outline = Color(0xFF2C313B),
    outlineStrong = Color(0xFF3B414D),
    ink = Color(0xFFE7E9EE),
    ink2 = Color(0xFFB4BAC5),
    ink3 = Color(0xFF959CA9),
    brand = Color(0xFF9DB9F5),
    brandContainer = Color(0xFF233457),
    onBrand = Color(0xFF0B1A38),
    solar = Color(0xFFF5B847),
    solarText = Color(0xFFF5B847),
    grid = Color(0xFF9AA8BF),
    home = Color(0xFF9DB9F5),
    ok = Color(0xFF5CC98C),
    okContainer = Color(0xFF1C3326),
    down = Color(0xFFF2726A),
    downContainer = Color(0xFF3A1D1D),
    onDownContainer = Color(0xFFFFD9D6),
    warn = Color(0xFFE6B45C),
    batteryHigh = Color(0xFF5CC98C),
    batteryMid = Color(0xFFE6B45C),
    batteryLow = Color(0xFFF2726A)
)

val LocalSolarColors = staticCompositionLocalOf { Light }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Solar {
    val colors: SolarColors
        @Composable @ReadOnlyComposable get() = LocalSolarColors.current

    /** Honour "Remove animations": decorative motion stops, state changes still crossfade. */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * The Material type scale in Roboto. Figures are regular weight with tabular digits, set
 * beside a smaller grey unit — the way SOLARMAN and every good meter writes "10.6 kWh".
 */
object SolarType {
    private val tnum = "tnum"

    val figureXL = TextStyle(fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = tnum)
    val figureL = TextStyle(fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 38.sp, fontFeatureSettings = tnum)
    val figure = TextStyle(fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp, fontFeatureSettings = tnum)
    val figureS = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = tnum)
    val unit = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

    val headline = TextStyle(fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp)
    val title = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp)
    val cardTitle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
    val bodyStrong = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)
}

@Composable
fun SolarTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) Dark else Light

    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val roles = if (dark) {
        darkColorScheme(
            primary = c.brand, onPrimary = c.onBrand, primaryContainer = c.brandContainer, onPrimaryContainer = c.ink,
            secondaryContainer = c.brandContainer, onSecondaryContainer = c.ink,
            background = c.bg, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, onSurfaceVariant = c.ink2,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceVariant, surfaceContainerHighest = c.surfaceVariant,
            outline = c.outlineStrong, outlineVariant = c.outline,
            error = c.down, errorContainer = c.downContainer, onErrorContainer = c.onDownContainer
        )
    } else {
        lightColorScheme(
            primary = c.brand, onPrimary = c.onBrand, primaryContainer = c.brandContainer, onPrimaryContainer = c.ink,
            secondaryContainer = c.brandContainer, onSecondaryContainer = c.ink,
            background = c.bg, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, onSurfaceVariant = c.ink2,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceVariant, surfaceContainerHighest = c.surfaceVariant,
            outline = c.outlineStrong, outlineVariant = c.outline,
            error = c.down, errorContainer = c.downContainer, onErrorContainer = c.onDownContainer
        )
    }

    CompositionLocalProvider(LocalSolarColors provides c, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = roles,
            typography = Typography().let {
                it.copy(
                    titleLarge = SolarType.title,
                    titleMedium = SolarType.cardTitle,
                    bodyMedium = SolarType.body,
                    labelLarge = SolarType.bodyStrong
                )
            },
            content = content
        )
    }
}
