package com.mcfrenchpants.activityledger.ui.history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R

/** Test tag of the History screen's root. */
const val HISTORY_SCREEN_TAG = "HistoryScreen"

/** The History destination. For now it shows its title only; the real screen replaces this body. */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.nav_history),
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(HISTORY_SCREEN_TAG),
    )
}
