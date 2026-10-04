package com.mcfrenchpants.activityledger.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mcfrenchpants.activityledger.ActivityLedgerApplication

/** Builds [TagsViewModel] from the tag management service held by [ActivityLedgerApplication]. */
class TagsViewModelFactory(private val application: ActivityLedgerApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TagsViewModel::class.java)) { "unsupported ViewModel ${modelClass.name}" }
        return TagsViewModel(application.tagManagementService) as T
    }
}
