package com.mcfrenchpants.activityledger.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Text

/**
 * SCAFFOLD PLACEHOLDER -- no product meaning.
 *
 * Exists only so the watch app module builds and launches. It shows the app name
 * as plain text. Real UI replaces this in a later step.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text(text = stringResource(R.string.app_name))
        }
    }
}
