package com.mcfrenchpants.activityledger.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.core.speech.PlatformSpeechTranscriber

/**
 * Builds [ExploreViewModel] from the repository, clock and lookup service held by
 * [ActivityLedgerApplication]. Hand-written on purpose: there is no DI framework (ADR-032).
 */
class ExploreViewModelFactory(private val application: ActivityLedgerApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ExploreViewModel::class.java)) { "unsupported ViewModel ${modelClass.name}" }
        val pipeline = application.capturePipeline
        return ExploreViewModel(
            repository = pipeline.repository,
            lookup = application.lookupService,
            // The one Android speech implementation (ADR-024), on the application context.
            transcriber = PlatformSpeechTranscriber(application.applicationContext),
            clock = pipeline.clock,
        ) as T
    }
}
