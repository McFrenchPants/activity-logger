package com.mcfrenchpants.activityledger.core.data

import android.content.Context
import androidx.room.Room
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabaseFactory
import com.mcfrenchpants.activityledger.core.data.id.UuidV7IdFactory
import com.mcfrenchpants.activityledger.core.data.repository.RoomActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import kotlinx.coroutines.Dispatchers
import java.time.Clock

/**
 * The phone app's entry point to persistence: a [LedgerRepository] (the activity contract
 * plus subject + action tags; usable wherever an ActivityRepository is expected) backed by
 * the on-disk ledger database, with time-ordered ids and blocking database work on the IO dispatcher.
 *
 * Opens the database (lazily, on first use); call once and share the result. Its signature
 * deliberately mentions only Android, java.time and core-domain types.
 */
public fun createActivityRepository(
    context: Context,
    clock: Clock = Clock.systemDefaultZone(),
): LedgerRepository = RoomActivityRepository(
    database = ActivityLedgerDatabaseFactory.create(context),
    idFactory = UuidV7IdFactory,
    clock = clock,
)

/**
 * For tests: the same [LedgerRepository] as [createActivityRepository], on a throwaway in-memory
 * database that disappears with the process. Same schema, same rules and same code -- the only
 * differences are that nothing is written to disk and that its work runs on the calling
 * coroutine's thread (queries are allowed on the main thread, no IO dispatcher), so a test's
 * own scheduler stays in charge of timing.
 *
 * Never use this in the shipped app: it keeps nothing.
 */
public fun createInMemoryActivityRepository(
    context: Context,
    clock: Clock = Clock.systemDefaultZone(),
): LedgerRepository = RoomActivityRepository(
    database = Room.inMemoryDatabaseBuilder(context.applicationContext, ActivityLedgerDatabase::class.java)
        .allowMainThreadQueries()
        .build(),
    idFactory = UuidV7IdFactory,
    clock = clock,
    dispatcher = Dispatchers.Unconfined,
)
