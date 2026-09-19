package com.mcfrenchpants.activityledger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mcfrenchpants.activityledger.R

/*
 * D3 typography: two voices, both bundled font resources (no downloadable fonts, no network).
 *
 * - Interpretation and UI: Instrument Sans (variable; wght axis pinned per weight).
 * - Evidence (the user's own words): Source Serif 4 Italic (variable; wght pinned at 400;
 *   the opsz axis is left at the font's default).
 *
 * Numerals use tabular figures everywhere ("tnum").
 */

private const val TABULAR_FIGURES = "tnum"

/** Instrument Sans at the two D3 weights, 400 and 600. */
val InstrumentSans: FontFamily = FontFamily(
    Font(
        R.font.instrument_sans,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.instrument_sans,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

/** Source Serif 4 Italic at weight 400: the evidence voice. */
val SourceSerif4Italic: FontFamily = FontFamily(
    Font(
        R.font.source_serif_4_italic,
        weight = FontWeight.Normal,
        style = FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
)

private fun sans(weight: FontWeight, size: Int, lineHeight: Int) = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontFeatureSettings = TABULAR_FIGURES,
)

/** Instrument Sans + tabular figures on an M3 default role D3 does not list (sizes kept). */
private fun TextStyle.inSans(): TextStyle =
    copy(fontFamily = InstrumentSans, fontFeatureSettings = TABULAR_FIGURES)

private val M3Defaults = Typography()

/** The M3 type scale: the six D3 roles exactly; every other role re-faced in Instrument Sans. */
internal val LedgerTypography: Typography = Typography(
    displayLarge = M3Defaults.displayLarge.inSans(),
    displayMedium = M3Defaults.displayMedium.inSans(),
    displaySmall = M3Defaults.displaySmall.inSans(),
    headlineLarge = M3Defaults.headlineLarge.inSans(),
    headlineMedium = sans(FontWeight.SemiBold, 28, 36),
    headlineSmall = M3Defaults.headlineSmall.inSans(),
    titleLarge = sans(FontWeight.SemiBold, 22, 28),
    titleMedium = sans(FontWeight.SemiBold, 17, 24),
    titleSmall = M3Defaults.titleSmall.inSans(),
    bodyLarge = sans(FontWeight.Normal, 16, 22),
    bodyMedium = sans(FontWeight.Normal, 14, 20),
    bodySmall = M3Defaults.bodySmall.inSans(),
    labelLarge = sans(FontWeight.SemiBold, 15, 20),
    labelMedium = M3Defaults.labelMedium.inSans(),
    labelSmall = M3Defaults.labelSmall.inSans(),
)

/**
 * The evidence style: Source Serif 4 Italic 400, line height 1.4x the font size. D3 allows
 * 14-28sp; the default is 16sp and callers may `copy(fontSize = ...)` within that range. The
 * line height is relative (em), so it stays 1.4x.
 */
internal val EvidenceTextStyle: TextStyle = TextStyle(
    fontFamily = SourceSerif4Italic,
    fontWeight = FontWeight.Normal,
    fontStyle = FontStyle.Italic,
    fontSize = 16.sp,
    lineHeight = 1.4.em,
    fontFeatureSettings = TABULAR_FIGURES,
)

internal val LocalEvidenceTextStyle = staticCompositionLocalOf { EvidenceTextStyle }
