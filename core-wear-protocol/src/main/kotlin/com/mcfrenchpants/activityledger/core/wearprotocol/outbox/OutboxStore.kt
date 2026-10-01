package com.mcfrenchpants.activityledger.core.wearprotocol.outbox

import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One durable outbox entry. [envelopeJson] is the already-validated `WireCodec.encode` output and
 * contains the raw capture text, so [toString] deliberately omits it (AGENTS.md #11).
 */
data class OutboxRecord(
    val captureId: String,
    val envelopeJson: String,
    val state: OutboxState,
    val attemptCount: Int,
    val nextAttemptAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
) {
    override fun toString(): String =
        "OutboxRecord(captureId=$captureId, state=$state, attemptCount=$attemptCount, " +
            "nextAttemptAtEpochMillis=$nextAttemptAtEpochMillis, createdAtEpochMillis=$createdAtEpochMillis)"
}

/** Persistence for outbox records, keyed on captureId. Implementations must be thread-safe. */
interface OutboxStore {
    /** Stores [record] only if no record with its captureId exists. Returns true if stored. */
    fun insertIfAbsent(record: OutboxRecord): Boolean

    fun get(captureId: String): OutboxRecord?

    /** All readable records, ordered by createdAt then captureId. */
    fun listAll(): List<OutboxRecord>

    /** Overwrites an existing record. Returns false (and stores nothing) if it does not exist. */
    fun replace(record: OutboxRecord): Boolean

    /** Removes a record. Returns true if one was removed. */
    fun remove(captureId: String): Boolean
}

private val recordOrder = compareBy<OutboxRecord>({ it.createdAtEpochMillis }, { it.captureId })

/** Volatile store for tests. */
class InMemoryOutboxStore : OutboxStore {
    private val records = HashMap<String, OutboxRecord>()

    @Synchronized
    override fun insertIfAbsent(record: OutboxRecord): Boolean {
        if (records.containsKey(record.captureId)) return false
        records[record.captureId] = record
        return true
    }

    @Synchronized
    override fun get(captureId: String): OutboxRecord? = records[captureId]

    @Synchronized
    override fun listAll(): List<OutboxRecord> = records.values.sortedWith(recordOrder)

    @Synchronized
    override fun replace(record: OutboxRecord): Boolean {
        if (!records.containsKey(record.captureId)) return false
        records[record.captureId] = record
        return true
    }

    @Synchronized
    override fun remove(captureId: String): Boolean = records.remove(captureId) != null
}

@Serializable
private class StoredRecord(
    val captureId: String,
    val envelopeJson: String,
    val state: OutboxState,
    val attemptCount: Int,
    val nextAttemptAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

/**
 * File-backed store: one `<captureId>.json` file per record in [dir].
 *
 * Writes are atomic: content goes to a temp file in the same directory, is fsynced, then moved
 * over the target with ATOMIC_MOVE (falling back to REPLACE_EXISTING if the file system refuses).
 * Leftover `.tmp` files and unreadable/corrupt record files are skipped on load, never thrown
 * about and never deleted. No exception raised here carries capture text.
 */
class FileOutboxStore(private val dir: File) : OutboxStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        dir.mkdirs()
    }

    override fun insertIfAbsent(record: OutboxRecord): Boolean = synchronized(lock) {
        requireSafeId(record.captureId)
        if (fileFor(record.captureId).exists()) return false
        write(record)
        true
    }

    override fun get(captureId: String): OutboxRecord? = synchronized(lock) {
        if (!isSafeId(captureId)) return null
        read(fileFor(captureId))?.takeIf { it.captureId == captureId }
    }

    override fun listAll(): List<OutboxRecord> = synchronized(lock) {
        val files = dir.listFiles() ?: return emptyList()
        files.asSequence()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .mapNotNull { file ->
                val record = read(file)
                if (record != null && file.name == record.captureId + SUFFIX) record else null
            }
            .sortedWith(recordOrder)
            .toList()
    }

    override fun replace(record: OutboxRecord): Boolean = synchronized(lock) {
        if (!isSafeId(record.captureId)) return false
        if (!fileFor(record.captureId).exists()) return false
        write(record)
        true
    }

    override fun remove(captureId: String): Boolean = synchronized(lock) {
        if (!isSafeId(captureId)) return false
        try {
            Files.deleteIfExists(fileFor(captureId).toPath())
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun fileFor(captureId: String) = File(dir, captureId + SUFFIX)

    private fun isSafeId(id: String): Boolean =
        try {
            UUID.fromString(id).toString().equals(id, ignoreCase = true)
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun requireSafeId(id: String) {
        require(isSafeId(id)) { "captureId is not a valid UUID" }
    }

    private fun read(file: File): OutboxRecord? =
        try {
            val s = json.decodeFromString(StoredRecord.serializer(), file.readText(Charsets.UTF_8))
            OutboxRecord(
                s.captureId, s.envelopeJson, s.state, s.attemptCount,
                s.nextAttemptAtEpochMillis, s.createdAtEpochMillis,
            )
        } catch (e: Exception) {
            null
        }

    private fun write(record: OutboxRecord) {
        ensureDir()
        val text = json.encodeToString(
            StoredRecord.serializer(),
            StoredRecord(
                record.captureId, record.envelopeJson, record.state, record.attemptCount,
                record.nextAttemptAtEpochMillis, record.createdAtEpochMillis,
            ),
        )
        val target = fileFor(record.captureId)
        val tmp = File(dir, record.captureId + "." + UUID.randomUUID() + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    private companion object {
        const val SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"
    }
}
