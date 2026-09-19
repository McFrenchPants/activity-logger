package com.mcfrenchpants.activityledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mcfrenchpants.activityledger.ui.navigation.LedgerNavigation
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme

/**
 * The phone app's single activity (UX_VISUAL_SPEC D1): edge-to-edge, themed with
 * [ActivityLedgerTheme], hosting the navigation shell ([LedgerNavigation]) with the Log and
 * History destinations.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ActivityLedgerTheme {
                LedgerNavigation()
            }
        }
    }
}
