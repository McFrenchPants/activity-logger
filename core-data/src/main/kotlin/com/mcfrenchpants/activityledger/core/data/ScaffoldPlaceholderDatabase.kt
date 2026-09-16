package com.mcfrenchpants.activityledger.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * SCAFFOLD PLACEHOLDER DATABASE -- no product meaning.
 *
 * This is not the app database. It holds one meaningless table purely to prove
 * that KSP runs the Room compiler and that a schema JSON is exported to the
 * tracked core-data/schemas directory.
 *
 * exportSchema MUST stay true here and on the real database: this project forbids
 * destructive migrations, and future migration tests read the exported schema
 * JSON to verify each version step.
 *
 * Delete this file when the real schema lands.
 */
@Database(
    entities = [ScaffoldPlaceholderEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class ScaffoldPlaceholderDatabase : RoomDatabase() {
    abstract fun scaffoldPlaceholderDao(): ScaffoldPlaceholderDao
}
