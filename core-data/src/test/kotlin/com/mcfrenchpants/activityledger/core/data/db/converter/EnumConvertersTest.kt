package com.mcfrenchpants.activityledger.core.data.db.converter

import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnumConvertersTest {

    private val c = EnumConverters()

    private fun <E : Enum<E>> checkEnum(
        enumName: String,
        entries: List<E>,
        encode: (E) -> String,
        decode: (String) -> E,
    ) {
        assertTrue(entries.isNotEmpty())
        for (e in entries) {
            val stored = encode(e)
            assertEquals(e.name, stored)
            assertEquals(e, decode(stored))
        }
        for (bad in listOf("NOT_A_CONSTANT", "", entries.first().name.lowercase())) {
            val ex = assertFailsWith<IllegalArgumentException>("decoding '$bad' as $enumName must throw") {
                decode(bad)
            }
            assertTrue(ex.message.orEmpty().contains(enumName), "message should name $enumName: ${ex.message}")
        }
    }

    @Test fun captureSource() =
        checkEnum("CaptureSource", CaptureSource.entries, c::fromCaptureSource, c::toCaptureSource)

    @Test fun processingState() =
        checkEnum("ProcessingState", ProcessingState.entries, c::fromProcessingState, c::toProcessingState)

    @Test fun canonicalActivityStatus() = checkEnum(
        "CanonicalActivityStatus",
        CanonicalActivityStatus.entries,
        c::fromCanonicalActivityStatus,
        c::toCanonicalActivityStatus,
    )

    @Test fun aliasSource() =
        checkEnum("AliasSource", AliasSource.entries, c::fromAliasSource, c::toAliasSource)

    @Test fun interpretationOperation() = checkEnum(
        "InterpretationOperation",
        InterpretationOperation.entries,
        c::fromInterpretationOperation,
        c::toInterpretationOperation,
    )

    @Test fun activityResolution() = checkEnum(
        "ActivityResolution",
        ActivityResolution.entries,
        c::fromActivityResolution,
        c::toActivityResolution,
    )

    @Test fun activityState() =
        checkEnum("ActivityState", ActivityState.entries, c::fromActivityState, c::toActivityState)

    @Test fun timePrecision() =
        checkEnum("TimePrecision", TimePrecision.entries, c::fromTimePrecision, c::toTimePrecision)

    @Test fun confidenceBand() =
        checkEnum("ConfidenceBand", ConfidenceBand.entries, c::fromConfidenceBand, c::toConfidenceBand)

    @Test fun validationStatus() =
        checkEnum("ValidationStatus", ValidationStatus.entries, c::fromValidationStatus, c::toValidationStatus)

    @Test fun visibilityStatus() =
        checkEnum("VisibilityStatus", VisibilityStatus.entries, c::fromVisibilityStatus, c::toVisibilityStatus)

    @Test fun correctionSource() =
        checkEnum("CorrectionSource", CorrectionSource.entries, c::fromCorrectionSource, c::toCorrectionSource)
}
