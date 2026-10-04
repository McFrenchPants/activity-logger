package com.mcfrenchpants.activityledger.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.speech.PlatformSpeechTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Builds [LogViewModel] from the process's single capture pipeline and services held by
 * [ActivityLedgerApplication]. Hand-written on purpose: there is no DI framework (ADR-032).
 *
 * The AI-readiness check asks [com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability.readiness]
 * only -- it never downloads anything -- and treats every state but READY as not ready.
 */
class LogViewModelFactory(private val application: ActivityLedgerApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LogViewModel::class.java)) { "unsupported ViewModel ${modelClass.name}" }
        val pipeline = application.capturePipeline
        return LogViewModel(
            repository = pipeline.repository,
            orchestrator = pipeline.taggedOrchestrator,
            resolution = application.taggedResolutionService,
            correction = application.taggedCorrectionService,
            // The one Android speech implementation (ADR-024), on the application context: it
            // outlives any Activity and never holds one.
            transcriber = PlatformSpeechTranscriber(application.applicationContext),
            clock = pipeline.clock,
            isAiReady = {
                withContext(Dispatchers.Default) { pipeline.capability.readiness() == ModelReadiness.READY }
            },
            zone = { ZoneId.systemDefault() },
        ) as T
    }
}
