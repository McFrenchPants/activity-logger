package com.mcfrenchpants.activityledger.core.domain.tagging

import java.util.Locale

/**
 * Turns the user's words for a tag into (a) a clean display name for a NEW tag and (b) a
 * comparison key used to decide whether two wordings are the same tag.
 *
 * **Display form ([cleanName]).** Whitespace runs collapse to one space and surrounding
 * punctuation is trimmed (as in `NameNormalizer`, but the user's casing is kept); then leading
 * determiners and possessives ([DETERMINERS]) are removed one word at a time ("all the dishes"
 * -> "dishes", "the Wi-Fi" -> "Wi-Fi"). Spelling and casing are otherwise kept as said.
 *
 * **Comparison form ([tokens] / [key]).** Lowercased with [Locale.ROOT] and split into words.
 * Hyphens and apostrophes are removed inside a word ("Wi-Fi" -> "wifi", "dog's" -> "dogs");
 * any other punctuation or symbol separates words. Leading determiners/possessives are dropped
 * (a word is dropped when it, or its singular, is in [DETERMINERS], so "hers" goes too). Each
 * remaining word is naively singularized by [singularize]. [key] joins the words with NO
 * separator, so "Wi-Fi" == "WiFi" == "wifi" and "lawn mower" == "lawnmower".
 *
 * Every function is idempotent on its own output: `cleanName(cleanName(x)) == cleanName(x)`,
 * `key(key(x)) == key(x)` and `tokens(tokens(x).joinToString(" ")) == tokens(x)`.
 *
 * **Verb forms ([verbForms] / [verbsMatch], TG1.4b).** An action's first word (the verb) may
 * be an inflected form of an existing action's verb ("cleaned" of "clean", "edging" of
 * "edge"). Only INFLECTED words are reduced, to a small set of possible base forms; an
 * uninflected word is only ever itself, so two different uninflected words ("tap"/"tape",
 * "hose"/"hoe") never match. Subjects and object words are never verb-reduced.
 *
 * Deliberately naive: no dictionary and no synonyms here (grass != lawn); the tiny synonym
 * groups live in [TagResolver] and only ever produce a "did you mean" question. These are
 * provisional rules, tuned on recordings (ADR-039 and its TG1.4b amendment).
 */
object TagNormalizer {

    /**
     * Leading words that carry no tag meaning and are stripped ("the", "my", "all the", ...).
     * Matched as whole words, case-insensitively, only at the start, repeatedly.
     */
    val DETERMINERS: Set<String> = setOf(
        "the", "a", "an", "my", "our", "your", "his", "her", "their", "its", "all", "some",
    )

    /**
     * Words ending in "-ies" longer than this many letters become "-y" ("batteries" ->
     * "battery"); shorter ones ("ties", "pies") just lose the "s".
     */
    const val IES_MIN_LENGTH_EXCLUSIVE: Int = 4

    /** Endings after which a word loses "-es" rather than "-s" ("glasses", "boxes", "dishes"). */
    val ES_PLURAL_ENDINGS: List<String> = listOf("sses", "xes", "ches", "shes")

    /**
     * Endings that keep their final "s" because the word is usually already singular
     * ("glass", "bus", "tennis", "gas").
     */
    val KEEP_S_ENDINGS: List<String> = listOf("ss", "us", "is", "as")

    /**
     * Shortest stem (in letters) left after stripping "-ing" or "-ed" for the word to count as
     * inflected; shorter ones are compared as themselves ("bring", "shed", "used").
     */
    const val VERB_STEM_MIN_LENGTH: Int = 3

    /**
     * The display name for a new tag made from [words], or null when nothing is left after
     * trimming punctuation and stripping leading determiners.
     */
    fun cleanName(words: String?): String? {
        if (words == null) return null
        var s = trimEdges(collapseWhitespace(words))
        while (s.isNotEmpty()) {
            val space = s.indexOf(' ')
            val first = if (space < 0) s else s.substring(0, space)
            if (!isDeterminer(comparisonWord(first))) break
            s = if (space < 0) "" else trimEdges(s.substring(space + 1))
        }
        return s.ifEmpty { null }
    }

    /**
     * The singularized comparison words of [words], leading determiners removed, in order.
     * Empty when [words] has no letters or digits left.
     */
    fun tokens(words: String?): List<String> = rawTokens(words).map(::singularize).filter { it.isNotEmpty() }

    /** The comparison key of [words]: [tokens] joined with no separator. Empty when no words. */
    fun key(words: String?): String = tokens(words).joinToString(separator = "")

    /**
     * The naive singular of one lowercase comparison word, applying the first rule that fits:
     * 1. longer than [IES_MIN_LENGTH_EXCLUSIVE] letters and ends in "-ies" -> "-y"
     *    ("batteries" -> "battery");
     * 2. ends in one of [ES_PLURAL_ENDINGS] -> drop "-es" ("glasses" -> "glass",
     *    "dishes" -> "dish", "boxes" -> "box");
     * 3. ends in "s" but not in one of [KEEP_S_ENDINGS] -> drop the "s" ("dogs" -> "dog",
     *    "houses" -> "house", "leaves" -> "leave");
     * otherwise unchanged ("grass", "gas", "bus").
     *
     * The result never ends in a removable "s", so applying it twice changes nothing. Plain
     * "-ses" is deliberately not rule 2: it would turn "houses" into "hous".
     */
    fun singularize(word: String): String = when {
        word.length > IES_MIN_LENGTH_EXCLUSIVE && word.endsWith("ies") -> word.dropLast(3) + "y"
        ES_PLURAL_ENDINGS.any { word.endsWith(it) } -> word.dropLast(2)
        word.endsWith("s") && KEEP_S_ENDINGS.none { word.endsWith(it) } -> word.dropLast(1)
        else -> word
    }

    /**
     * The possible base forms of one lowercase verb [word] (TG1.4b rule 1), always including
     * [word] itself. Single pass, the first rule that fits, nothing re-applied to its result:
     * - "-ied": longer than [IES_MIN_LENGTH_EXCLUSIVE] letters -> "-y" ("emptied" -> "empty"),
     *   else -> "-ie" ("tied" -> "tie");
     * - "-ing" or "-ed" (not "-eed": "weed", "need", "seed" are base words) with at least
     *   [VERB_STEM_MIN_LENGTH] letters left: the strip ("taped" -> "tap"), the strip + "e"
     *   ("tape"), and, when the strip ends in a doubled consonant, the strip undoubled
     *   ("mopped" -> "mop");
     * - "-oes" -> drop "-es" ("goes" -> "go");
     * - otherwise a plural-like "-s"/"-es" that [singularize] removes ("washes" -> "wash",
     *   "changes" -> "change", "mows" -> "mow").
     * A word none of these fits is uninflected and its only form is itself. The forms are a
     * comparison aid only and are never shown to anyone.
     */
    fun verbForms(word: String): Set<String> {
        val out = linkedSetOf(word)
        when {
            word.endsWith("ied") && word.length > IES_MIN_LENGTH_EXCLUSIVE -> out += word.dropLast(3) + "y"
            word.endsWith("ied") -> out += word.dropLast(1)
            word.endsWith("ing") && word.length - 3 >= VERB_STEM_MIN_LENGTH -> addBases(word.dropLast(3), out)
            word.endsWith("ed") && !word.endsWith("eed") && word.length - 2 >= VERB_STEM_MIN_LENGTH ->
                addBases(word.dropLast(2), out)
            word.endsWith("oes") -> out += word.dropLast(2)
            else -> singularize(word).takeIf { it != word && it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /**
     * True when two lowercase verbs can be the same verb: their [verbForms] share a form. Two
     * different uninflected words never match ("tap"/"tape", "hose"/"hoe", "clean"/"cleanse");
     * an inflected word matches its base ("taped"/"tape", "mowing"/"mow"). Ambiguity ("taped"
     * matches both "tap" and "tape") is resolved by the caller ([TagResolver]), never here.
     */
    fun verbsMatch(a: String, b: String): Boolean = a == b || verbForms(a).any { it in verbForms(b) }

    /** The verb of an ACTION: its first comparison word, lowercased, NOT singularized; null when none. */
    fun actionVerb(words: String?): String? = rawTokens(words).firstOrNull()

    /** The object words of an ACTION: [tokens] after the first word (singularized). */
    fun actionObjects(words: String?): List<String> = tokens(words).drop(1)

    private fun addBases(strip: String, out: MutableSet<String>) {
        out += strip
        out += strip + "e"
        val n = strip.length
        if (n >= 2 && strip[n - 1] == strip[n - 2] && isConsonant(strip[n - 1])) out += strip.dropLast(1)
    }

    private fun isConsonant(c: Char): Boolean = c in 'a'..'z' && c !in "aeiou"

    /**
     * Lowercase comparison words of [words] with leading determiners removed, NOT singularized.
     * Used by the decision policy's word checks (vague verbs, filler words).
     */
    internal fun rawTokens(words: String?): List<String> {
        if (words == null) return emptyList()
        val all = splitWords(words.lowercase(Locale.ROOT))
        var start = 0
        while (start < all.size && isDeterminer(all[start])) start++
        return all.subList(start, all.size)
    }

    private fun isDeterminer(word: String): Boolean =
        word in DETERMINERS || singularize(word) in DETERMINERS

    /** One word in comparison form: lowercase letters and digits only. */
    private fun comparisonWord(word: String): String =
        splitWords(word.lowercase(Locale.ROOT)).joinToString(separator = "")

    private fun splitWords(lower: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in lower) {
            when {
                Character.isLetterOrDigit(c) -> current.append(c)
                isJoiner(c) -> Unit
                else -> if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** Characters removed inside a word without splitting it: hyphens/dashes and apostrophes. */
    private fun isJoiner(c: Char): Boolean =
        Character.getType(c).toByte() == Character.DASH_PUNCTUATION ||
            c == '\'' || c == '’' || c == '‘' || c == 'ʼ'

    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (isSpace(c)) {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun trimEdges(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && (isPunctuation(text[start]) || isSpace(text[start]))) start++
        while (end > start && (isPunctuation(text[end - 1]) || isSpace(text[end - 1]))) end--
        return text.substring(start, end)
    }

    private fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}
