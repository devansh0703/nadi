package com.nadi.health.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Metrolist design system — light mode.
 *
 * Seeded from Metrolist's `DefaultThemeColor = 0xFFED5564` (the coral accent
 * their Material 3 tonal palette is generated from), written out as static M3
 * roles so the build needs no dynamic-color dependency: warm-white canvas,
 * white sheets, warm-grey tonal cards, coral primary.
 */

// ── Brand tokens ────────────────────────────────────────────────────────────
object Metro {
    /** Metrolist DefaultThemeColor. */
    val Coral = Color(0xFFED5564)
    /** Tone-40 equivalent — for solid coral fills that carry text. */
    val CoralDeep = Color(0xFFB31E33)
    val CoralContainer = Color(0xFFFFDAD9)
    /** Warm-white canvas, one step under pure white so white cards read. */
    val Canvas = Color(0xFFFFF7F6)
    /** Tonal (warm grey) card fill. */
    val CardTint = Color(0xFFF4EAEA)
    /** Hero wash for headline cards. */
    val HeroGradient = listOf(Color(0xFFFF8A7A), Color(0xFFED5564), Color(0xFFC21F3C))
}

// ── Color roles ─────────────────────────────────────────────────────────────
private val MetroLight = lightColorScheme(
    primary = Color(0xFFB31E33),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF3F0009),
    inversePrimary = Color(0xFFFFB3AB),

    secondary = Color(0xFF77565A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDADC),
    onSecondaryContainer = Color(0xFF2C1517),

    tertiary = Color(0xFF775700),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF271900),

    background = Metro.Canvas,
    onBackground = Color(0xFF221C1D),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF221C1D),
    surfaceVariant = Metro.CardTint,
    onSurfaceVariant = Color(0xFF524446),
    surfaceTint = Color(0xFFB31E33),

    outline = Color(0xFF857375),
    outlineVariant = Color(0xFFC9BDBD),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    inverseSurface = Color(0xFF382E2F),
    inverseOnSurface = Color(0xFFFFEDEE),
    scrim = Color(0xFF000000)
)

// ── Type ────────────────────────────────────────────────────────────────────
// Two tiers: a tight, heavy display voice for numbers and screen titles, and a
// calm, roomy voice for anything that has to be read at length.
val Typography = Typography(
    displayLarge = TextStyle(
        fontSize = 57.sp, lineHeight = 60.sp,
        fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontSize = 42.sp, lineHeight = 46.sp,
        fontWeight = FontWeight.Black, letterSpacing = (-1).sp
    ),
    displaySmall = TextStyle(
        fontSize = 34.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp
    ),
    headlineLarge = TextStyle(
        fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp
    )
)

private val MetroShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(12.dp)
)

/**
 * Light-only, matching Metrolist's light theme. [darkTheme] and [dynamicColor]
 * stay in the signature as explicit overrides for previews, but both default
 * off so the coral identity is deterministic on every wallpaper.
 */
@Composable
fun NadiTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MetroLight,
        typography = Typography,
        shapes = MetroShapes,
        content = content
    )
}
