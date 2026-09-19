package com.mcfrenchpants.activityledger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * D3 colour tokens (docs/UX_VISUAL_SPEC.md, "Color tokens"). The values in the D3 table are
 * copied here exactly and pinned by ColorTokensTest.
 *
 * Roles M3 has but D3 does not list (secondary, tertiary, the remaining surface containers,
 * inverse roles, onBackground, onError) are DERIVED from D3 tokens below so nothing falls back
 * to M3's baseline purple. There is one accent (ledger green): secondary/tertiary reuse it.
 */

// ---- Light (D3) ----
private val LightBackground = Color(0xFFF7F4EE)
private val LightSurface = Color(0xFFFFFDF9)
private val LightSurfaceContainer = Color(0xFFEFEAE1)
private val LightSurfaceContainerHigh = Color(0xFFE7E1D6)
private val LightOutlineVariant = Color(0xFFD6CEBF)
private val LightOutline = Color(0xFF9C9486)
private val LightOnSurface = Color(0xFF22201C)
private val LightOnSurfaceVariant = Color(0xFF6B655B)
private val LightPrimary = Color(0xFF2F6B55)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD5E8DD)
private val LightOnPrimaryContainer = Color(0xFF0F3527)
private val LightReview = Color(0xFF855610)
private val LightReviewContainer = Color(0xFFF3E4C4)
private val LightOnReviewContainer = Color(0xFF3D2804)
private val LightError = Color(0xFFA33B2C)
private val LightErrorContainer = Color(0xFFF6D9D3)
private val LightOnErrorContainer = Color(0xFF44120A)

// ---- Dark (D3) ----
private val DarkBackground = Color(0xFF161513)
private val DarkSurface = Color(0xFF1E1C19)
private val DarkSurfaceContainer = Color(0xFF282521)
private val DarkSurfaceContainerHigh = Color(0xFF322E29)
private val DarkOutlineVariant = Color(0xFF403B35)
private val DarkOutline = Color(0xFF7A7368)
private val DarkOnSurface = Color(0xFFEDE8DF)
private val DarkOnSurfaceVariant = Color(0xFFA69F93)
private val DarkPrimary = Color(0xFF8FC9AE)
private val DarkOnPrimary = Color(0xFF0B2A1E)
private val DarkPrimaryContainer = Color(0xFF1E4838)
private val DarkOnPrimaryContainer = Color(0xFFCDEBDC)
private val DarkReview = Color(0xFFE4BA6C)
private val DarkReviewContainer = Color(0xFF3B2E16)
private val DarkOnReviewContainer = Color(0xFFF6E3BD)
private val DarkError = Color(0xFFF0A193)
private val DarkErrorContainer = Color(0xFF4B221B)
private val DarkOnErrorContainer = Color(0xFFFAD9D2)

/** The light M3 scheme. Static: dynamic colour is off (D3). */
internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = DarkPrimary,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightPrimaryContainer,
    onSecondaryContainer = LightOnPrimaryContainer,
    tertiary = LightPrimary,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightPrimaryContainer,
    onTertiaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainerHigh,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightSurface,
    error = LightError,
    onError = LightOnPrimary,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceBright = LightSurface,
    surfaceDim = LightSurfaceContainerHigh,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHigh,
)

/** The dark M3 scheme. Static: dynamic colour is off (D3). */
internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = LightPrimary,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
    tertiary = DarkPrimary,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkPrimaryContainer,
    onTertiaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainerHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    error = DarkError,
    onError = DarkErrorContainer,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceBright = DarkSurfaceContainerHigh,
    surfaceDim = DarkBackground,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHigh,
)

/**
 * D3's custom colour roles, which M3's [ColorScheme] has no slot for. Read them with
 * `ActivityLedgerTheme.extendedColors` inside [ActivityLedgerTheme].
 */
@Immutable
data class ExtendedColors(
    /** Needs-review accent (icon, label). */
    val review: Color,
    /** Fill behind needs-review content. */
    val reviewContainer: Color,
    /** Text/icons on [reviewContainer]. */
    val onReviewContainer: Color,
)

internal val LightExtendedColors = ExtendedColors(
    review = LightReview,
    reviewContainer = LightReviewContainer,
    onReviewContainer = LightOnReviewContainer,
)

internal val DarkExtendedColors = ExtendedColors(
    review = DarkReview,
    reviewContainer = DarkReviewContainer,
    onReviewContainer = DarkOnReviewContainer,
)

internal val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
