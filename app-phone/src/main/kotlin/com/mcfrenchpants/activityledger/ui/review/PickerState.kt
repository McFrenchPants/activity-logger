package com.mcfrenchpants.activityledger.ui.review

import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity

/**
 * An open shared activity picker (Log card actions, History resolution sheet).
 *
 * @property activities The ACTIVE catalog to choose from.
 * @property startWithNewActivity Open directly on the new-activity name field.
 */
data class PickerState(
    val activities: List<CatalogActivity>,
    val startWithNewActivity: Boolean,
)
