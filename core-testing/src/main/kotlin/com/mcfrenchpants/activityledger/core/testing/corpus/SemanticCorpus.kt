package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The semantic regression corpus, loaded from the classpath resource [RESOURCE_PATH].
 *
 * The resource lives in core-testing's main resources so the same file is reachable from
 * core-testing's own tests, host tests in other modules, and on-device instrumented tests.
 *
 * @property schemaVersion Version of the corpus document shape.
 * @property catalogs Named catalog fixtures, by name.
 * @property cases All cases, in file order.
 * @property sha256 Lowercase hex SHA-256 of the exact resource bytes. Recordings made against
 *   the corpus store it so a replay can tell whether the corpus has changed since.
 */
class SemanticCorpus private constructor(
    val schemaVersion: Int,
    val catalogs: Map<String, CatalogFixture>,
    val cases: List<CorpusCase>,
    val sha256: String,
) {

    /** The case with [id], or null. */
    fun case(id: String): CorpusCase? = cases.firstOrNull { it.id == id }

    /**
     * The catalog the case's pipeline run must see: the fixture's activities with their FIXED
     * ids, names and aliases normalized with [NameNormalizer], and no last occurrence.
     *
     * @throws IllegalArgumentException if the case names an unknown fixture.
     */
    fun catalogFor(case: CorpusCase): List<CatalogActivity> {
        val fixture = requireNotNull(catalogs[case.catalog]) {
            "case ${case.id} refers to unknown catalog fixture '${case.catalog}'"
        }
        return fixture.activities.map { activity ->
            CatalogActivity(
                id = activity.id,
                displayName = activity.displayName,
                normalizedName = NameNormalizer.normalize(activity.displayName),
                normalizedAliases = activity.aliases.map(NameNormalizer::normalize),
                lastOccurredAt = null,
            )
        }
    }

    companion object {
        /** Classpath location of the corpus (absolute resource name). */
        const val RESOURCE_PATH: String = "/semantic-corpus/corpus.json"

        private val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }

        /** Loads and decodes the corpus from the classpath. */
        fun load(): SemanticCorpus {
            val bytes = SemanticCorpus::class.java.getResourceAsStream(RESOURCE_PATH)
                ?.use { it.readBytes() }
                ?: error("semantic corpus resource $RESOURCE_PATH not found on the classpath")
            return parse(bytes)
        }

        /** Decodes corpus JSON [bytes] (UTF-8). [sha256] is computed over exactly these bytes. */
        fun parse(bytes: ByteArray): SemanticCorpus {
            val document = JSON.decodeFromString(CorpusDocument.serializer(), bytes.toString(Charsets.UTF_8))
            return SemanticCorpus(
                schemaVersion = document.schemaVersion,
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
