package com.mcfrenchpants.activityledger.core.testing.corpus

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The question corpus (DH4.4), loaded from the classpath resource [RESOURCE_PATH]. Separate from
 * [SemanticCorpus] and [TagCorpus] so their files and recordings stay byte-identical.
 *
 * @property schemaVersion Version of the document shape.
 * @property description What the corpus is.
 * @property catalogs Named catalog fixtures (tags plus logged entries), by name.
 * @property cases All cases, in file order.
 * @property sha256 Lowercase hex SHA-256 of the exact resource bytes, so a recording can tell
 *   whether the question corpus has changed since it was made.
 */
class QuestionCorpus private constructor(
    val schemaVersion: Int,
    val description: String,
    val catalogs: Map<String, QuestionCatalogFixture>,
    val cases: List<QuestionCorpusCase>,
    val sha256: String,
) {

    /** The case with [id], or null. */
    fun case(id: String): QuestionCorpusCase? = cases.firstOrNull { it.id == id }

    /** The catalog fixture of [case]; throws when it is missing (an integrity error). */
    fun fixtureOf(case: QuestionCorpusCase): QuestionCatalogFixture =
        catalogs[case.catalog] ?: error("question corpus case '${case.id}' refers to a missing catalog")

    companion object {
        /** Classpath location of the question corpus (absolute resource name). */
        const val RESOURCE_PATH: String = "/semantic-corpus/question-corpus.json"

        private val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }

        /** Reads the exact resource bytes from the classpath. */
        fun resourceBytes(): ByteArray =
            QuestionCorpus::class.java.getResourceAsStream(RESOURCE_PATH)
                ?.use { it.readBytes() }
                ?: error("question corpus resource $RESOURCE_PATH not found on the classpath")

        /** Loads and decodes the question corpus from the classpath. */
        fun load(): QuestionCorpus = parse(resourceBytes())

        /** Decodes question corpus JSON [bytes] (UTF-8). [sha256] is computed over exactly these bytes. */
        fun parse(bytes: ByteArray): QuestionCorpus {
            val document = JSON.decodeFromString(QuestionCorpusDocument.serializer(), bytes.toString(Charsets.UTF_8))
            return QuestionCorpus(
                schemaVersion = document.schemaVersion,
                description = document.description,
                catalogs = document.catalogs,
                cases = document.cases,
                sha256 = sha256Hex(bytes),
            )
        }

        private const val HEX = "0123456789abcdef"

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return buildString(digest.size * 2) {
                for (b in digest) {
                    val v = b.toInt() and 0xff
                    append(HEX[v ushr 4])
                    append(HEX[v and 0x0f])
                }
            }
        }
    }
}
