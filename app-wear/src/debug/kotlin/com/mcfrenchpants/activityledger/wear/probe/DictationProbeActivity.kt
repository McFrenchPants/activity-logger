package com.mcfrenchpants.activityledger.wear.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text

/**
 * DEBUG-ONLY measurement tool, hand-run by the owner: does Wear speech-to-text work with the
 * network off? Not product UI.
 *
 * Privacy (AGENTS.md #11): transcripts, partials and results are shown on screen only and are
 * never passed to any logging call. Only fixed tokens and error code names are logged.
 */
class DictationProbeActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null

    // Compose-observed state, written from main-thread callbacks.
    private var inAppStatus by mutableStateOf("idle")
    private var inAppText by mutableStateOf("")
    private var inAppMs by mutableStateOf<Long?>(null)
    private var pendingStartInApp = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingStartInApp) {
                startInApp()
            } else if (!granted) {
                inAppStatus = "RECORD_AUDIO denied"
            }
            pendingStartInApp = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProbeScreen(
                inAppStatus = inAppStatus,
                inAppText = inAppText,
                inAppMs = inAppMs,
                onInAppClick = ::requestInApp,
            )
        }
    }

    override fun onStop() {
        releaseRecognizer()
        super.onStop()
    }

    override fun onDestroy() {
        releaseRecognizer()
        super.onDestroy()
    }

    private fun requestInApp() {
        releaseRecognizer()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startInApp()
        } else {
            pendingStartInApp = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startInApp() {
        inAppText = ""
        inAppMs = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            inAppStatus = "recognition not available"
            Log.i(TAG, "in-app: not available")
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                inAppStatus = "listening"
            }

            override fun onBeginningOfSpeech() {
                inAppStatus = "hearing speech"
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                inAppStatus = "processing"
            }

            override fun onError(error: Int) {
                val name = errorName(error)
                Log.i(TAG, "in-app error: $name")
                inAppStatus = "error: $name ($error)"
                releaseRecognizer()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (inAppMs == null && !text.isNullOrEmpty()) {
                    inAppMs = SystemClock.elapsedRealtime() - startedAt
                }
                inAppText = text ?: "(no result)"
                inAppStatus = "final"
                releaseRecognizer()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    if (inAppMs == null) inAppMs = SystemClock.elapsedRealtime() - startedAt
                    inAppText = text
                    inAppStatus = "partial"
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        inAppStatus = "starting"
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            Log.i(TAG, "in-app start failed: ${e.javaClass.simpleName}")
            inAppStatus = "start failed: ${e.javaClass.simpleName}"
            releaseRecognizer()
        }
    }

    private fun releaseRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.cancel()
        } catch (_: RuntimeException) {
        }
        try {
            r.destroy()
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val TAG = "DictationProbe"

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_UNKNOWN_$code"
        }
    }
}

@Composable
private fun ProbeScreen(
    inAppStatus: String,
    inAppText: String,
    inAppMs: Long?,
    onInAppClick: () -> Unit,
) {
    var systemText by remember { mutableStateOf("") }
    var systemMs by remember { mutableStateOf<Long?>(null) }
    var systemStartedAt by remember { mutableStateOf(0L) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val first = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
        } else {
            null
        }
        if (first.isNullOrEmpty()) {
            systemText = "cancelled / nothing returned (resultCode=${result.resultCode})"
            systemMs = null
        } else {
            systemText = first
            systemMs = SystemClock.elapsedRealtime() - systemStartedAt
        }
    }

    val textColor = Color.White
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Dictation Probe", color = textColor, fontSize = 18.sp)

        Button(
            onClick = {
                systemText = "waiting..."
                systemMs = null
                systemStartedAt = SystemClock.elapsedRealtime()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                try {
                    launcher.launch(intent)
                } catch (e: RuntimeException) {
                    systemText = "launch failed: ${e.javaClass.simpleName}"
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("System dictation")
        }
        Text("Result: $systemText", color = textColor, fontSize = 16.sp)
        Text("First text after: ${systemMs?.let { "$it ms" } ?: "-"}", color = textColor, fontSize = 16.sp)

        Button(
            onClick = onInAppClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("In-app recognizer")
        }
        Text("Status: $inAppStatus", color = textColor, fontSize = 16.sp)
        Text("Text: $inAppText", color = textColor, fontSize = 16.sp)
        Text("First text after: ${inAppMs?.let { "$it ms" } ?: "-"}", color = textColor, fontSize = 16.sp)
    }
}
