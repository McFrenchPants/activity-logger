package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.lookup.DateWords
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * The QUESTION corpus model (DH4.4): typed or spoken questions about the logged history, each
 * against a synthetic catalog fixture of subject/action tags and logged entries, with the
 * filter values the real LookupService should resolve it to.
 *
 * A separate test set from corpus.json and tag-corpus.json (same idea as the tag corpus,
 * ADR-033). Every field without a default is REQUIRED in question-corpus.json, including
 * nullable ones; only `note` and the fixtures' `description` are optional, and an expected range
 * is written either as {"preset": "ALL_TIME"} or as {"from": ..., "to": ...}.
 *
 * Expected values are the correct PRODUCT behaviour (program logic decides the kind from the
 * text first, then the model's kind; dates come only from TemporalRangeResolver; tags only from
 * TagResolver), never what a particular model currently answers. Everything is synthetic.
 */

/** Why a question corpus case exists. */
@Serializable
enum class QuestionCaseGroup {
    /** "When did I last ...", "When was the last time ...". */
    LAST_TIME,

    /** "How many times ...", "How much ...", usually with date words. */
    COUNT,

    /** "How often ...", "How regularly ...". */
    HOW_OFTEN,

    /** "What did I do to ...", "Show me ...", "List ..." about something in particular. */
    LIST,

    /** A question that names only a stretch of time ("What did I do last week?"). */
    DATE_ONLY,

    /** Something never logged (or nothing logged at all): nothing may be answered. */
    NOT_LOGGED,

    /** Aliases, near matches, shared actions, missing question marks, unusual date words. */
    TRICKY,
}

/** What [com.mcfrenchpants.activityledger.core.domain.lookup.LookupService.ask] should end in. */
@Serializable
enum class QuestionOutcome {
    /** `LookupOutcome.Answer`: the question resolved to tags and matched logged entries. */
    ANSWER,

    /** `LookupOutcome.Browse`: a question about a stretch of time, naming nothing in particular. */
    BROWSE,

    /** `LookupOutcome.NotEnoughHistory`: nothing to answer from. */
    NOT_ENOUGH_HISTORY,
}

/**
 * One logged entry in a catalog fixture; becomes a `LookupEntry` row (durationSeconds null).
 *
 * @property id Fixed id; the entry's occurrence id.
 * @property subjectId Id of a subject in the same fixture.
 * @property actionId Id of an action in the same fixture.
 * @property occurredAt ISO offset date-time.
 */
@Serializable
data class QuestionFixtureEntry(
    val id: String,
    val subjectId: String,
    val actionId: String,
    val occurredAt: String,
) {
    /** [occurredAt] parsed. */
    val occurredDateTime: OffsetDateTime get() = OffsetDateTime.parse(occurredAt)
}

/**
 * A named catalog fixture: existing subject and action tags plus logged entries.
 *
 * @property description Optional explanation of the fixture.
 */
@Serializable
data class QuestionCatalogFixture(
    val description: String? = null,
    val subjects: List<FixtureTag>,
    val actions: List<FixtureTag>,
    val entries: List<QuestionFixtureEntry>,
)

/**
 * The expected date range: exactly one of [preset] (only "ALL_TIME") and the [from]/[to] pair
 * of inclusive ISO local dates. Compared by dates only, never by label.
 */
@Serializable
data class ExpectedQuestionRange(
    val preset: String? = null,
    val from: String? = null,
    val to: String? = null,
) {
    init {
        require((preset != null) != (from != null || to != null)) {
            "an expected range has either a preset or from/to"
        }
        require(preset == null || preset == ALL_TIME) { "the only expected range preset is $ALL_TIME" }
        require((from == null) == (to == null)) { "an expected range needs both from and to" }
    }

    /** The range as comparable dates. */
    fun toDates(): QuestionRangeDates =
        if (preset != null) QuestionRangeDates.ALL_TIME else QuestionRangeDates(LocalDate.parse(from), LocalDate.parse(to))

    companion object {
        const val ALL_TIME: String = "ALL_TIME"
    }
}

/**
 * A date range as plain dates: [from]..[to] inclusive, or both null for all time.
 */
data class QuestionRangeDates(val from: LocalDate?, val to: LocalDate?) {
    init {
        require((from == null) == (to == null)) { "a range has both ends or neither" }
        require(from == null || !from.isAfter(to)) { "range start after its end" }
    }

    /** True for the all-time range. */
    val isAllTime: Boolean get() = from == null

    override fun toString(): String = if (from == null) "ALL_TIME" else "$from..$to"

    companion object {
        val ALL_TIME: QuestionRangeDates = QuestionRangeDates(null, null)
    }
}

/**
 * The correct product result of one question.
 *
 * @property outcome What the lookup should end in.
 * @property subjectIds For ANSWER: the acceptable resolved subject tag ids; empty means the
 *   target's subject must be unset. Empty for any other outcome.
 * @property actionIds The same for the action.
 * @property mustNotMatch Tag ids (either kind) that must never be in the target.
 * @property kind The expected question kind.
 * @property allowedKinds Other acceptable kinds (never includes [kind]).
 * @property range The expected range (dates only).
 * @property dateWords What should become of the question's date words.
 * For NOT_ENOUGH_HISTORY, [kind], [range] and [dateWords] are still given but not scored.
 */
@Serializable
data class ExpectedQuestion(
    val outcome: QuestionOutcome,
    val subjectIds: List<String>,
    val actionIds: List<String>,
    val mustNotMatch: List<String>,
    val kind: QuestionKind,
    val allowedKinds: List<QuestionKind>,
    val range: ExpectedQuestionRange,
    val dateWords: DateWords,
) {
    /** [kind] plus [allowedKinds]. */
    val acceptableKinds: Set<QuestionKind> get() = setOf(kind) + allowedKinds
}

/**
 * One question corpus case.
 *
 * @property id Stable kebab-case identifier; recordings and reports refer to it.
 * @property group Why the case exists.
 * @property catalog Name of a [QuestionCatalogFixture] in [QuestionCorpus.catalogs].
 * @property question The question, verbatim (synthetic).
 * @property today ISO local date the question is asked on.
 * @property zoneId IANA zone of the user.
 * @property firstDayOfWeek First day of the user's week (a [DayOfWeek] name).
 * @property expected The product-correct result.
 * @property note Optional explanation of a non-obvious expectation.
 */
@Serializable
data class QuestionCorpusCase(
    val id: String,
    val group: QuestionCaseGroup,
    val catalog: String,
    val question: String,
    val today: String,
    val zoneId: String,
    val firstDayOfWeek: String,
    val expected: ExpectedQuestion,
    val note: String? = null,
) {
    /** [today] parsed. */
    val todayDate: LocalDate get() = LocalDate.parse(today)

    /** [zoneId] parsed. */
    val zone: ZoneId get() = ZoneId.of(zoneId)

    /** [firstDayOfWeek] parsed. */
    val weekStart: DayOfWeek get() = DayOfWeek.valueOf(firstDayOfWeek)
}

/** The on-disk document shape of question-corpus.json. */
@Serializable
internal data class QuestionCorpusDocument(
    val schemaVersion: Int,
    val description: String,
    val catalogs: Map<String, QuestionCatalogFixture>,
    val cases: List<QuestionCorpusCase>,
)
