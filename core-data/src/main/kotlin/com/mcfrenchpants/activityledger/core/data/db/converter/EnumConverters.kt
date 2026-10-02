package com.mcfrenchpants.activityledger.core.data.db.converter

import androidx.room.TypeConverter
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus

/**
 * Explicit enum <-> TEXT converters for every domain vocabulary stored in the
 * database. The stored value is always the enum constant's `name`.
 *
 * Decoding is strict: a stored value that is not a constant name throws
 * [IllegalArgumentException] naming the enum. It never falls back to a default,
 * because silently reinterpreting persisted data would corrupt the ledger.
 * (Nullable columns are handled by Room, which only calls these for non-null values.)
 */
internal class EnumConverters {

    @TypeConverter fun fromCaptureSource(value: CaptureSource): String = value.name
    @TypeConverter fun toCaptureSource(value: String): CaptureSource = decode(value)

    @TypeConverter fun fromProcessingState(value: ProcessingState): String = value.name
    @TypeConverter fun toProcessingState(value: String): ProcessingState = decode(value)

    @TypeConverter fun fromCanonicalActivityStatus(value: CanonicalActivityStatus): String = value.name
    @TypeConverter fun toCanonicalActivityStatus(value: String): CanonicalActivityStatus = decode(value)

    @TypeConverter fun fromAliasSource(value: AliasSource): String = value.name
    @TypeConverter fun toAliasSource(value: String): AliasSource = decode(value)

    @TypeConverter fun fromInterpretationOperation(value: InterpretationOperation): String = value.name
    @TypeConverter fun toInterpretationOperation(value: String): InterpretationOperation = decode(value)

    @TypeConverter fun fromActivityResolution(value: ActivityResolution): String = value.name
    @TypeConverter fun toActivityResolution(value: String): ActivityResolution = decode(value)

    @TypeConverter fun fromActivityState(value: ActivityState): String = value.name
    @TypeConverter fun toActivityState(value: String): ActivityState = decode(value)

    @TypeConverter fun fromTimePrecision(value: TimePrecision): String = value.name
    @TypeConverter fun toTimePrecision(value: String): TimePrecision = decode(value)

    @TypeConverter fun fromConfidenceBand(value: ConfidenceBand): String = value.name
    @TypeConverter fun toConfidenceBand(value: String): ConfidenceBand = decode(value)

    @TypeConverter fun fromValidationStatus(value: ValidationStatus): String = value.name
    @TypeConverter fun toValidationStatus(value: String): ValidationStatus = decode(value)

    @TypeConverter fun fromVisibilityStatus(value: VisibilityStatus): String = value.name
    @TypeConverter fun toVisibilityStatus(value: String): VisibilityStatus = decode(value)

    @TypeConverter fun fromCorrectionSource(value: CorrectionSource): String = value.name
    @TypeConverter fun toCorrectionSource(value: String): CorrectionSource = decode(value)

    @TypeConverter fun fromTagStatus(value: TagStatus): String = value.name
    @TypeConverter fun toTagStatus(value: String): TagStatus = decode(value)

    private inline fun <reified E : Enum<E>> decode(value: String): E =
        enumValues<E>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException(
                "Unknown ${E::class.simpleName} value stored in database: '$value'",
            )
}
