package com.mcfrenchpants.activityledger.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mcfrenchpants.activityledger.core.data.db.converter.EnumConverters
import com.mcfrenchpants.activityledger.core.data.db.dao.FixtureInsertDao
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity

/**
 * The phone's authoritative Room database.
 *
 * exportSchema MUST stay true: this project forbids destructive migrations, and
 * migration tests read the tracked schema JSON in core-data/schemas to verify
 * each version step. Every foreign key is NO ACTION (never CASCADE / SET NULL /
 * SET DEFAULT) so history can never be silently deleted or orphaned; Room turns
 * `PRAGMA foreign_keys` on for this database because the schema declares them.
 */
@Database(
    entities = [
        RawCaptureEntity::class,
        CanonicalActivityEntity::class,
        ActivityAliasEntity::class,
        InterpretationEntity::class,
        ActivityOccurrenceEntity::class,
        CorrectionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
internal abstract class ActivityLedgerDatabase : RoomDatabase() {
    abstract fun fixtureInsertDao(): FixtureInsertDao
}
