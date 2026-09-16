package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity

/**
 * Minimal, temporary insert-only DAO used by schema tests to write fixture rows.
 *
 * It is NOT the data-access API: the next task replaces it with DAOs that expose
 * restricted, intention-revealing write operations (no generic update of
 * raw_text, transactional accept/correct operations). Do not add queries,
 * updates or deletes here.
 */
@Dao
internal interface FixtureInsertDao {
    @Insert fun insertRawCapture(row: RawCaptureEntity)

    @Insert fun insertCanonicalActivity(row: CanonicalActivityEntity)

    @Insert fun insertActivityAlias(row: ActivityAliasEntity)

    @Insert fun insertInterpretation(row: InterpretationEntity)

    @Insert fun insertActivityOccurrence(row: ActivityOccurrenceEntity)

    @Insert fun insertCorrection(row: CorrectionEntity)
}
