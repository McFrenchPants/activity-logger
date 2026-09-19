package com.mcfrenchpants.activityledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle

/**
 * The app's Material 3 theme (docs/UX_VISUAL_SPEC.md D3): static brand colours -- dynamic
 * colour is deliberately OFF so "saved" and "needs review" never re-tint with the wallpaper --
 * light and dark following the system setting, plus the D3 type scale and shapes.
 *
 * The custom review roles and the evidence text style are provided alongside [MaterialTheme];
 * read them through [ActivityLedgerTheme.extendedColors] and
 * [ActivityLedgerTheme.evidenceTextStyle].
 */
@Composable
fun ActivityLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalEvidenceTextStyle provides EvidenceTextStyle,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LedgerTypography,
            shapes = LedgerM3Shapes,
            content = content,
        )
    }
}

/** Accessors for the theme values [MaterialTheme] has no slot for. */
object ActivityLedgerTheme {
    /** The D3 review / reviewContainer / onReviewContainer roles for the current mode. */
    val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    /** The evidence voice (Source Serif 4 Italic) for anything the user actually said. */
    val evidenceTextStyle: TextStyle
        @Composable @ReadOnlyComposable get() = LocalEvidenceTextStyle.current
}
