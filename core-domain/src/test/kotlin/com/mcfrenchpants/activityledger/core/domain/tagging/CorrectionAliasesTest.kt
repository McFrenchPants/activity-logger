package com.mcfrenchpants.activityledger.core.domain.tagging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorrectionAliasesTest {

    private fun subject(id: String, name: String, vararg aliases: String) =
        KnownTag(id, TagKind.SUBJECT, name, aliases.toList())

    private fun action(id: String, name: String, vararg aliases: String) =
        KnownTag(id, TagKind.ACTION, name, aliases.toList())

    private val furnace = subject("s-furnace", "furnace")
    private val hotTub = subject("s-hot-tub", "hot tub", "spa")
    private val wifi = subject("s-wifi", "WiFi", "internet")
    private val lawn = subject("s-lawn", "lawn")
    private val changeFilter = action("a-change-filter", "change filter")
    private val changeOil = action("a-change-oil", "change oil")
    private val mow = action("a-mow", "mow")
    private val edge = action("a-edge", "edge")

    private val catalog = TagCatalog(
        subjects = listOf(furnace, hotTub, wifi, lawn),
        actions = listOf(changeFilter, changeOil, mow, edge),
        pairs = listOf(KnownPair(furnace.id, changeFilter.id), KnownPair(hotTub.id, changeFilter.id)),
    )

    private fun learn(
        extractedSubject: String?,
        extractedAction: String?,
        previousSubjectId: String? = furnace.id,
        previousActionId: String? = changeFilter.id,
        newSubject: TagChoice = TagChoice.Existing(hotTub.id),
        newAction: TagChoice = TagChoice.Existing(changeFilter.id),
        catalog: TagCatalog = this.catalog,
    ) = CorrectionAliases.aliasesToLearn(
        extractedSubject, extractedAction, previousSubjectId, previousActionId, newSubject, newAction, catalog,
    )

    // --- the motivating cases ----------------------------------------------------

    @Test
    fun furnaceCorrectedToHotTubIsNeverLearned() {
        // "furnace" already names another subject: learning it would send every furnace entry to hot tub.
        assertEquals(LearnedAliases.NONE, learn("furnace", "change filter"))
        assertEquals(LearnedAliases.NONE, learn("the Furnaces", "change filter"))
    }

    @Test
    fun aliasOfAnotherTagIsNeverLearned() {
        assertNull(learn("Internet", "change filter").subjectAlias)
    }

    @Test
    fun subjectThatIsOnlyTheActionsObjectIsNeverLearned() {
        // "filter" / "change filter": the real subject was dropped (ADR-039 amendment rule 4).
        assertNull(learn("filter", "change filter").subjectAlias)
        assertNull(learn("the filters", "changed the filter").subjectAlias)
        assertNull(learn("filter", "change filter", previousSubjectId = null).subjectAlias)
    }

    @Test
    fun genuineNewWordingForTheCorrectedSubjectIsLearned() {
        assertEquals(LearnedAliases("jacuzzi", null), learn("  jacuzzi ", "change filter"))
        // A subject word that is not among the action's objects is fine even with an object.
        assertEquals("pool", learn("pool", "change filter").subjectAlias)
        // An action without object words never triggers the object-only rule.
        assertEquals("deck", learn("deck", "stain", newAction = TagChoice.New("stain")).subjectAlias)
    }

    // --- rule 1: only a changed side learns ----------------------------------------

    @Test
    fun keepingTheSubjectButChangingTheActionLearnsOnlyTheAction() {
        val result = learn(
            extractedSubject = "jacuzzi",
            extractedAction = "scrub filter",
            previousSubjectId = hotTub.id,
            previousActionId = changeOil.id,
            newSubject = TagChoice.Existing(hotTub.id),
            newAction = TagChoice.Existing(changeFilter.id),
        )
        assertEquals(LearnedAliases(subjectAlias = null, actionAlias = "scrub filter"), result)
    }

    @Test
    fun unchangedSidesLearnNothing() {
        assertEquals(
            LearnedAliases.NONE,
            learn("jacuzzi", "scrub filter", previousSubjectId = hotTub.id, previousActionId = changeFilter.id),
        )
        // A New name that converges on the previous tag (same key, or one of its aliases) is no change.
        assertNull(learn("router", "reset", previousSubjectId = wifi.id, newSubject = TagChoice.New("Wi-Fi")).subjectAlias)
        assertNull(learn("jacuzzi", "drain", previousSubjectId = hotTub.id, newSubject = TagChoice.New("Spa")).subjectAlias)
    }

    @Test
    fun untaggedEntryCountsAsChanged() {
        val result = learn("jacuzzi", "scrub filter", previousSubjectId = null, previousActionId = null)
        assertEquals(LearnedAliases("jacuzzi", "scrub filter"), result)
    }

    @Test
    fun newNameConvergingOnAnotherTagLearnsForThatTag() {
        // "Spa" is hot tub's alias, so the chosen tag is hot tub: its own name is not learned...
        assertNull(learn("hot tubs", "drain", newSubject = TagChoice.New("Spa")).subjectAlias)
        // ...but new wording is.
        assertEquals("jacuzzi", learn("jacuzzi", "drain", newSubject = TagChoice.New("Spa")).subjectAlias)
    }

    @Test
    fun existingTagMissingFromCatalogOrBlankNewNameLearnsNothing() {
        assertNull(learn("jacuzzi", "drain", newSubject = TagChoice.Existing("s-merged")).subjectAlias)
        assertNull(learn("jacuzzi", "drain", newSubject = TagChoice.New("  the ")).subjectAlias)
        assertNull(learn("jacuzzi", "scrub filter", newAction = TagChoice.Existing(furnace.id)).actionAlias, "wrong kind")
    }

    // --- rules 2 and 3: blank, or nothing new to learn ---------------------------------

    @Test
    fun blankWordsAreNeverLearned() {
        assertEquals(LearnedAliases.NONE, learn(null, null, previousActionId = mow.id))
        assertEquals(LearnedAliases.NONE, learn("   ", "  ", previousActionId = mow.id))
        assertEquals(LearnedAliases.NONE, learn("the", "!!", previousActionId = mow.id))
    }

    @Test
    fun wordsWithTheChosenTagsKeyAreNotLearned() {
        // Wi-Fi / WiFi: equal key, nothing to learn.
        assertNull(learn("Wi-Fi", "reset", previousSubjectId = lawn.id, newSubject = TagChoice.Existing(wifi.id)).subjectAlias)
        assertNull(learn("hot tubs", "change filter").subjectAlias)
        // Already an alias of the chosen tag.
        assertNull(learn("the Spa", "change filter").subjectAlias)
        assertNull(learn("hot tub", "change filters", previousActionId = mow.id).actionAlias)
    }

    // --- rule 5: new-name rules and filler words ----------------------------------------

    @Test
    fun wordsFailingTheNewNameRulesAreNeverLearned() {
        assertNull(learn("thing", "change filter").subjectAlias)
        assertNull(learn("garden stuff", "change filter").subjectAlias)
        assertNull(learn("jacuzzi", "fix things", previousActionId = mow.id).actionAlias)
        assertNull(learn("I jacuzzi", "change filter").subjectAlias)
        assertNull(learn("jacuzzi", "finished filter", previousActionId = mow.id).actionAlias)
        assertNull(learn("1234", "change filter").subjectAlias)
    }

    // --- rule 6: actions that already mean another action ---------------------------------

    @Test
    fun actionThatIsAnInflectionOfAnotherActionIsNeverLearned() {
        // "changing oil" means the existing "change oil": never an alias of "change filter".
        assertNull(learn("hot tub", "changing oil", previousSubjectId = hotTub.id, previousActionId = changeOil.id).actionAlias)
        assertNull(learn("hot tub", "mowing", previousSubjectId = hotTub.id, previousActionId = mow.id).actionAlias)
    }

    @Test
    fun actionThatIsASynonymOfAnotherActionIsNeverLearned() {
        // "swap oil" is asked about as "change oil": never an alias of "change filter".
        assertNull(learn("hot tub", "swap oil", previousSubjectId = hotTub.id, previousActionId = mow.id).actionAlias)
    }

    @Test
    fun actionThatOnlyMeansTheChosenActionIsLearned() {
        // "swap filter" is a synonym of the chosen "change filter" only: safe and useful to learn.
        assertEquals(
            "swap filter",
            learn("hot tub", "swap filter", previousSubjectId = hotTub.id, previousActionId = mow.id).actionAlias,
        )
    }

    @Test
    fun actionWordsNamingAnotherActionAreNeverLearned() {
        assertNull(learn("hot tub", "Mow", previousSubjectId = hotTub.id, previousActionId = mow.id, newAction = TagChoice.New("cut grass")).actionAlias)
        assertNull(learn("hot tub", "mow", previousSubjectId = hotTub.id, previousActionId = mow.id).actionAlias)
    }

    // --- rule 7: junk subjects ----------------------------------------------------------

    @Test
    fun junkSubjectsAreNeverLearned() {
        // Only the action's verb.
        assertNull(learn("edging", "edge", previousActionId = edge.id, newAction = TagChoice.Existing(edge.id)).subjectAlias)
        assertNull(learn("mowing", "mow").subjectAlias)
        // Only time words.
        assertNull(learn("Saturday morning", "change filter").subjectAlias)
        assertNull(learn("last week", "change filter").subjectAlias)
    }

    // --- empty catalog and determinism ----------------------------------------------------

    @Test
    fun emptyCatalogLearnsGenuinelyNewWordingForNewTags() {
        val result = learn(
            "jacuzzi", "swap filter", previousSubjectId = null, previousActionId = null,
            newSubject = TagChoice.New("Hot tub"), newAction = TagChoice.New("change filter"), catalog = TagCatalog.EMPTY,
        )
        assertEquals(LearnedAliases("jacuzzi", "swap filter"), result)

        val same = learn(
            "hot tubs", "changing filter", previousSubjectId = null, previousActionId = null,
            newSubject = TagChoice.New("Hot tub"), newAction = TagChoice.New("change filter"), catalog = TagCatalog.EMPTY,
        )
        assertEquals("changing filter", same.actionAlias, "an inflected verb has a different key; harmless to learn")
        assertNull(same.subjectAlias, "same key as the new name")

        val filter = learn(
            "filter", "change filter", previousSubjectId = null, previousActionId = null,
            newSubject = TagChoice.New("Hot tub"), newAction = TagChoice.New("change filter"), catalog = TagCatalog.EMPTY,
        )
        assertEquals(LearnedAliases.NONE, filter)
    }

    @Test
    fun sameInputGivesSameAnswer() {
        val a = learn("jacuzzi", "scrub filter", previousActionId = mow.id)
        val b = learn("jacuzzi", "scrub filter", previousActionId = mow.id)
        assertEquals(a, b)
        assertEquals(LearnedAliases("jacuzzi", "scrub filter"), a)
    }
}
