package com.mcfrenchpants.activityledger.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mcfrenchpants.activityledger.ActivityLedgerApplication

/**
 * Builds [HistoryViewModel] from the repository and services held by [ActivityLedgerApplication].
 * Hand-written on purpose: there is no DI framework (ADR-032).
 */
class HistoryViewModelFactory(private val application: ActivityLedgerApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HistoryViewModel::class.java)) { "unsupported ViewModel ${modelClass.name}" }
        val pipeline = application.capturePipeline
        return HistoryViewModel(
            repository = pipeline.repository,
            reviewResolutionService = application.reviewResolutionService,
            clock = pipeline.clock,
        ) as T
    }
}
