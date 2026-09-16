package com.mcfrenchpants.activityledger.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The single place the real, on-disk [ActivityLedgerDatabase] is configured.
 *
 * Rules (enforced by tests in the test source set):
 * - Schema changes are applied ONLY by the explicit [MIGRATIONS] list. The same
 *   list is what the migration tests run, so a migration that ships is a
 *   migration that was tested.
 * - There is NO destructive fallback of any kind: a missing migration path must
 *   fail the open, never drop the user's history.
 *   (DestructiveMigrationGuardTest proves this behaviourally.)
 * - No logging and no callbacks that read or write row contents.
 *
 * Not yet wired into app-phone.
 */
internal object ActivityLedgerDatabaseFactory {

    /** File name of the production database (in the app's databases directory). */
    const val DATABASE_NAME: String = "activity_ledger.db"

    /**
     * Every schema migration, in version order. Empty while version 1 is the
     * only schema. Production and the migration tests both use this list.
     */
    val MIGRATIONS: List<Migration> = emptyList()

    /**
     * The fully configured builder. Exposed so tests can add test-only settings
     * (e.g. main-thread queries) on top of the exact production configuration;
     * production code should call [create].
     */
    fun builder(context: Context, name: String = DATABASE_NAME): RoomDatabase.Builder<ActivityLedgerDatabase> =
        Room.databaseBuilder(context.applicationContext, ActivityLedgerDatabase::class.java, name)
            .addMigrations(*MIGRATIONS.toTypedArray())

    fun create(context: Context, name: String = DATABASE_NAME): ActivityLedgerDatabase =
        builder(context, name).build()
}
