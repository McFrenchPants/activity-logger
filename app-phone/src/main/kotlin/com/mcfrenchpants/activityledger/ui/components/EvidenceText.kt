package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme

/**
 * The user's own words (raw capture text, alias phrases), always in curly quotes and in the
 * evidence voice (Source Serif 4 Italic), so the original phrase is never mistaken for app output
 * (UX_VISUAL_SPEC D3).
 *
 * Never truncated: evidence may only be shortened together with an expand affordance (section
 * 5), and none exists yet, so there is no maxLines or ellipsis here at all.
 */
@Composable
fun EvidenceText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ActivityLedgerTheme.evidenceTextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text = quoteEvidence(text), modifier = modifier, style = style, color = color)
}

/** Wraps [text] in curly double quotes. */
internal fun quoteEvidence(text: String): String = "“$text”"
