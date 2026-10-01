package com.mcfrenchpants.activityledger.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import com.mcfrenchpants.activityledger.core.speech.PlatformSpeechTranscriber
import com.mcfrenchpants.activityledger.wear.capture.CaptureScreen
import com.mcfrenchpants.activityledger.wear.capture.CaptureSessionController
import com.mcfrenchpants.activityledger.wear.capture.CaptureUiState
import com.mcfrenchpants.activityledger.wear.capture.HapticPlayer
import com.mcfrenchpants.activityledger.wear.capture.PlaceholderCaptureSink
import com.mcfrenchpants.activityledger.wear.capture.UnavailableReason
import com.mcfrenchpants.activityledger.wear.capture.VibratorHapticPlayer
import com.mcfrenchpants.activityledger.wear.capture.hapticOnTransition
import kotlinx.coroutines.launch

/**
 * Opens straight into listening. Captures go to a placeholder sink until WC1.5 wires the outbox.
 *
 * The real ambient-mode callback is deferred to WC1.6; until then the screen is always told
 * `ambient = false`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: CaptureSessionController
    private lateinit var haptics: HapticPlayer
    private var permissionDenied by mutableStateOf(false)
    private var started = false

    // No Fragment on the classpath, so the lint rule about old Fragment versions is a false positive.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionDenied = false
                if (started) beginSession()
            } else {
                permissionDenied = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CaptureSessionController(
            transcriber = PlatformSpeechTranscriber.preferringOffline(this),
            sink = PlaceholderCaptureSink(),
            scope = lifecycleScope,
        )
        haptics = VibratorHapticPlayer(this)
        lifecycleScope.launch {
            var previous: CaptureUiState? = null
            controller.state.collect { current ->
                haptics.play(hapticOnTransition(previous, current))
                previous = current
            }
        }
        setContent {
            val state by controller.state.collectAsState()
            val shown = if (permissionDenied) {
                CaptureUiState.Unavailable(UnavailableReason.PERMISSION_MISSING)
            } else {
                state
            }
            MaterialTheme {
                CaptureScreen(
                    state = shown,
                    ambient = false,
                    onRetry = controller::retry,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginSession()
        } else if (!permissionDenied) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        started = false
        controller.stop()
        super.onStop()
    }

    private fun beginSession() {
        controller.start()
    }
}
