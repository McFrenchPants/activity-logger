package com.mcfrenchpants.activityledger.core.data

import android.content.Context
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabaseFactory
import com.mcfrenchpants.activityledger.core.data.id.UuidV7IdFactory
import com.mcfrenchpants.activityledger.core.data.repository.RoomActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import java.time.Clock

/**
 * The phone app's entry point to persistence: an [ActivityRepository] backed by the on-disk
 * ledger database, with time-ordered ids and blocking database work on the IO dispatcher.
 *
 * Opens the database (lazily, on first use); call once and share the result. Its signature
 * deliberately mentions only Android, java.time and core-domain types.
 */
public fun createActivityRepository(
    context: Context,
    clock: Clock = Clock.systemDefaultZone(),
): ActivityRepository = RoomActivityRepository(
    database = ActivityLedgerDatabaseFactory.create(context),
    idFactory = UuidV7IdFactory,
    clock = clock,
)
