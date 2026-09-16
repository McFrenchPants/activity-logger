package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState

/** The original captured/transcribed user input. `raw_text` is logically immutable. */
@Entity(
    tableName = "raw_captures",
    indices = [
        Index(value = ["captured_at"]),
        Index(value = ["processing_state"]),
    ],
)
internal data class RawCaptureEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source") val source: CaptureSource,
    @ColumnInfo(name = "source_surface") val sourceSurface: String?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    /** IANA zone id in effect at capture time, e.g. "Europe/London". */
    @ColumnInfo(name = "captured_zone_id") val capturedZoneId: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "speech_confidence") val speechConfidence: Double?,
    @ColumnInfo(name = "speech_alternatives_json") val speechAlternativesJson: String?,
    @ColumnInfo(name = "processing_state") val processingState: ProcessingState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
