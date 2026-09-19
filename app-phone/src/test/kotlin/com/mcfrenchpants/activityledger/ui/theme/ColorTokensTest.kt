package com.mcfrenchpants.activityledger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the D3 colour tokens (UX_VISUAL_SPEC "Color tokens") in the theme's light and dark
 * schemes. The expected hex strings are typed here independently of Color.kt, so a typo in
 * either place fails the test.
 */
class ColorTokensTest {

    private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    private fun Color.alpha255(): Int = (toArgb() ushr 24) and 0xFF

    @Test
    fun lightSchemeMatchesD3() {
        val s = LightColorScheme
        val expected = mapOf(
            "primary" to (s.primary to "#2F6B55"),
            "onPrimary" to (s.onPrimary to "#FFFFFF"),
            "primaryContainer" to (s.primaryContainer to "#D5E8DD"),
            "onPrimaryContainer" to (s.onPrimaryContainer to "#0F3527"),
            "background" to (s.background to "#F7F4EE"),
            "surface" to (s.surface to "#FFFDF9"),
            "surfaceContainer" to (s.surfaceContainer to "#EFEAE1"),
            "surfaceContainerHigh" to (s.surfaceContainerHigh to "#E7E1D6"),
            "outlineVariant" to (s.outlineVariant to "#D6CEBF"),
            "outline" to (s.outline to "#9C9486"),
            "onSurface" to (s.onSurface to "#22201C"),
            "onSurfaceVariant" to (s.onSurfaceVariant to "#6B655B"),
            "error" to (s.error to "#A33B2C"),
            "errorContainer" to (s.errorContainer to "#F6D9D3"),
            "onErrorContainer" to (s.onErrorContainer to "#44120A"),
            "review" to (LightExtendedColors.review to "#855610"),
            "reviewContainer" to (LightExtendedColors.reviewContainer to "#F3E4C4"),
            "onReviewContainer" to (LightExtendedColors.onReviewContainer to "#3D2804"),
        )
        assertTokens(expected)
    }

    @Test
    fun darkSchemeMatchesD3() {
        val s = DarkColorScheme
        val expected = mapOf(
            "primary" to (s.primary to "#8FC9AE"),
            "onPrimary" to (s.onPrimary to "#0B2A1E"),
            "primaryContainer" to (s.primaryContainer to "#1E4838"),
            "onPrimaryContainer" to (s.onPrimaryContainer to "#CDEBDC"),
            "background" to (s.background to "#161513"),
            "surface" to (s.surface to "#1E1C19"),
            "surfaceContainer" to (s.surfaceContainer to "#282521"),
            "surfaceContainerHigh" to (s.surfaceContainerHigh to "#322E29"),
            "outlineVariant" to (s.outlineVariant to "#403B35"),
            "outline" to (s.outline to "#7A7368"),
            "onSurface" to (s.onSurface to "#EDE8DF"),
            "onSurfaceVariant" to (s.onSurfaceVariant to "#A69F93"),
            "error" to (s.error to "#F0A193"),
            "errorContainer" to (s.errorContainer to "#4B221B"),
            "onErrorContainer" to (s.onErrorContainer to "#FAD9D2"),
            "review" to (DarkExtendedColors.review to "#E4BA6C"),
            "reviewContainer" to (DarkExtendedColors.reviewContainer to "#3B2E16"),
            "onReviewContainer" to (DarkExtendedColors.onReviewContainer to "#F6E3BD"),
        )
        assertTokens(expected)
    }

    private fun assertTokens(expected: Map<String, Pair<Color, String>>) {
        expected.forEach { (role, pair) ->
            val (actual, hex) = pair
            assertEquals("$role hex", hex, actual.hex())
            assertEquals("$role alpha", 0xFF, actual.alpha255())
        }
    }
}
