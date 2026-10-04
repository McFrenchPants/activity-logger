package com.mcfrenchpants.activityledger.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.core.speech.PlatformSpeechTranscriber

/**
 * Builds [AskViewModel] from the lookup service held by [ActivityLedgerApplication].
 * Hand-written on purpose: there is no DI framework (ADR-032).
 */
class AskViewModelFactory(private val application: ActivityLedgerApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AskViewModel::class.java)) { "unsupported ViewModel ${modelClass.name}" }
        return AskViewModel(
            lookup = application.lookupService,
            // The one Android speech implementation (ADR-024), on the application context.
            transcriber = PlatformSpeechTranscriber(application.applicationContext),
        ) as T
    }
}
