package com.mcfrenchpants.activityledger.core.testing.corpus

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The tag corpus (subject + action redesign), loaded from the classpath resource
 * [RESOURCE_PATH]. Separate from [SemanticCorpus] so corpus.json and its recordings stay
 * byte-identical.
 *
 * @property schemaVersion Version of the document shape.
 * @property catalogs Named tag catalog fixtures, by name.
 * @property cases All cases, in file order.
 * @property sha256 Lowercase hex SHA-256 of the exact resource bytes, so a future recording can
 *   tell whether the tag corpus has changed since it was made.
 */
class TagCorpus private constructor(
    val schemaVersion: Int,
    val catalogs: Map<String, TagCatalogFixture>,
    val cases: List<TagCorpusCase>,
    val sha256: String,
) {

    /** The case with [id], or null. */
    fun case(id: String): TagCorpusCase? = cases.firstOrNull { it.id == id }

    companion object {
        /** Classpath location of the tag corpus (absolute resource name). */
        const val RESOURCE_PATH: String = "/semantic-corpus/tag-corpus.json"

        private val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }

        /** Reads the exact resource bytes from the classpath. */
        fun resourceBytes(): ByteArray =
            TagCorpus::class.java.getResourceAsStream(RESOURCE_PATH)
                ?.use { it.readBytes() }
                ?: error("tag corpus resource $RESOURCE_PATH not found on the classpath")

        /** Loads and decodes the tag corpus from the classpath. */
        fun load(): TagCorpus = parse(resourceBytes())

        /** Decodes tag corpus JSON [bytes] (UTF-8). [sha256] is computed over exactly these bytes. */
        fun parse(bytes: ByteArray): TagCorpus {
            val document = JSON.decodeFromString(TagCorpusDocument.serializer(), bytes.toString(Charsets.UTF_8))
            return TagCorpus(
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
