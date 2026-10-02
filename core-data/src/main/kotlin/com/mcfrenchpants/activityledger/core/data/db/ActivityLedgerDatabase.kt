package com.mcfrenchpants.activityledger.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mcfrenchpants.activityledger.core.data.db.converter.EnumConverters
import com.mcfrenchpants.activityledger.core.data.db.dao.ActionAliasDao
import com.mcfrenchpants.activityledger.core.data.db.dao.ActionDao
import com.mcfrenchpants.activityledger.core.data.db.dao.ActivityAliasDao
import com.mcfrenchpants.activityledger.core.data.db.dao.ActivityOccurrenceDao
import com.mcfrenchpants.activityledger.core.data.db.dao.CanonicalActivityDao
import com.mcfrenchpants.activityledger.core.data.db.dao.CorrectionDao
import com.mcfrenchpants.activityledger.core.data.db.dao.InterpretationDao
import com.mcfrenchpants.activityledger.core.data.db.dao.LedgerWriteDao
import com.mcfrenchpants.activityledger.core.data.db.dao.RawCaptureDao
import com.mcfrenchpants.activityledger.core.data.db.dao.SubjectAliasDao
import com.mcfrenchpants.activityledger.core.data.db.dao.SubjectDao
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectEntity

/**
 * The phone's authoritative Room database.
 *
 * exportSchema MUST stay true: this project forbids destructive migrations, and
 * migration tests read the tracked schema JSON in core-data/schemas to verify
 * each version step. Every foreign key is NO ACTION (never CASCADE / SET NULL /
 * SET DEFAULT) so history can never be silently deleted or orphaned; Room turns
 * `PRAGMA foreign_keys` on for this database because the schema declares them.
 *
 * Version 2 (ADR-040) adds subject/action tags, their aliases, the nullable
 * subject/action pair on canonical_activities and duration columns; the 1 -> 2
 * step is the additive MIGRATION_1_2 in ActivityLedgerDatabaseFactory.MIGRATIONS.
 */
@Database(
    entities = [
        RawCaptureEntity::class,
        CanonicalActivityEntity::class,
        ActivityAliasEntity::class,
        InterpretationEntity::class,
        ActivityOccurrenceEntity::class,
        CorrectionEntity::class,
        SubjectEntity::class,
        ActionEntity::class,
        SubjectAliasEntity::class,
        ActionAliasEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
internal abstract class ActivityLedgerDatabase : RoomDatabase() {
    // The data layer's complete write surface. There is no @Update, @Upsert,
    // @Delete or DELETE query anywhere, and every @Insert uses the default ABORT
    // strategy (REPLACE would delete-and-rewrite rows). Occurrences and
    // corrections are written only by LedgerWriteDao's two @Transaction
    // operations. DaoWriteSurfaceGuardTest enforces this by inspecting every
    // DAO returned from this class.
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun canonicalActivityDao(): CanonicalActivityDao
    abstract fun activityAliasDao(): ActivityAliasDao
    abstract fun interpretationDao(): InterpretationDao
    abstract fun activityOccurrenceDao(): ActivityOccurrenceDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun ledgerWriteDao(): LedgerWriteDao
    abstract fun subjectDao(): SubjectDao
    abstract fun actionDao(): ActionDao
    abstract fun subjectAliasDao(): SubjectAliasDao
    abstract fun actionAliasDao(): ActionAliasDao
}
