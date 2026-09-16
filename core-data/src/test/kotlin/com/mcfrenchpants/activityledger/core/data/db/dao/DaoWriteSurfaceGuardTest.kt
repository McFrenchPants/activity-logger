package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.OnConflictStrategy
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural guard over the data layer's complete write surface.
 *
 * DAOs are discovered from ActivityLedgerDatabase's abstract, parameterless DAO
 * accessors (and cross-checked against an explicit list, so adding a DAO forces
 * a conscious update here). Room annotations have CLASS retention, so they are
 * read from the compiled class files via [ClassFileAnnotations].
 *
 * Rules: no @Update / @Upsert / @Delete / @RawQuery; no @Query starting with
 * DELETE, INSERT or REPLACE; an UPDATE @Query may target only raw_captures
 * (setting only processing_state and updated_at) or activity_occurrences (never
 * setting id, raw_capture_id, captured_at or created_at); every @Insert uses
 * the default ABORT conflict strategy. Occurrences and corrections are written
 * only by LedgerWriteDao: an UPDATE of activity_occurrences, or an @Insert
 * taking ActivityOccurrenceEntity or CorrectionEntity (directly, as an array, or
 * as a type argument such as List<CorrectionEntity>), anywhere else is a
 * violation.
 */
class DaoWriteSurfaceGuardTest {

    private val expectedDaos = setOf(
        RawCaptureDao::class.java,
        CanonicalActivityDao::class.java,
        ActivityAliasDao::class.java,
        InterpretationDao::class.java,
        ActivityOccurrenceDao::class.java,
        CorrectionDao::class.java,
        LedgerWriteDao::class.java,
    )

    private fun daoTypesFromDatabase(): Set<Class<*>> =
        ActivityLedgerDatabase::class.java.declaredMethods
            .filter { Modifier.isAbstract(it.modifiers) && it.parameterCount == 0 }
            .map { it.returnType }
            .toSet()

    @Test
    fun databaseExposesExactlyTheKnownDaosAndEachIsAnnotatedDao() {
        val found = daoTypesFromDatabase()
        assertEquals(expectedDaos.map { it.name }.sorted(), found.map { it.name }.sorted())
        for (dao in found) {
            val info = ClassFileAnnotations.read(dao)
            assertTrue(info.annotations.any { it.descriptor == DAO }, "${dao.simpleName} is not annotated @Dao")
        }
    }

    @Test
    fun daoWriteSurfaceIsRestricted() {
        val violations = mutableListOf<String>()
        var inserts = 0
        var updates = 0
        for (dao in daoTypesFromDatabase() + expectedDaos) {
            for (method in ClassFileAnnotations.read(dao).methods) {
                val where = "${dao.simpleName}.${method.name}"
                for (annotation in method.annotations) {
                    when (annotation.descriptor) {
                        UPDATE, UPSERT, DELETE, RAW_QUERY ->
                            violations += "$where carries forbidden ${annotation.descriptor}"
                        INSERT -> {
                            inserts++
                            val strategy = annotation.values["onConflict"] ?: OnConflictStrategy.ABORT
                            if (strategy != OnConflictStrategy.ABORT) {
                                violations += "$where @Insert onConflict=$strategy (must be ABORT)"
                            }
                            val parameters = (method.signature ?: method.descriptor).substringBefore(')')
                            val ledgerOnly = LEDGER_ONLY_ENTITIES.filter { parameters.contains(it) }
                            if (ledgerOnly.isNotEmpty() && dao != LedgerWriteDao::class.java) {
                                violations += "$where @Insert of $ledgerOnly outside LedgerWriteDao"
                            }
                        }
                        QUERY -> {
                            val sql = (annotation.values["value"] as String).trim().replace(Regex("\\s+"), " ")
                            val verb = sql.substringBefore(' ').uppercase()
                            when (verb) {
                                "DELETE", "INSERT", "REPLACE" -> violations += "$where @Query starts with $verb"
                                "UPDATE" -> {
                                    updates++
                                    checkUpdate(dao, sql)?.let { violations += "$where: $it" }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), violations, "DAO write-surface violations")
        // Sanity: the scan really saw the annotations (a reader bug must not pass vacuously).
        assertTrue(inserts >= 7, "expected to see the @Insert methods, saw $inserts")
        assertTrue(updates >= 3, "expected to see the UPDATE queries, saw $updates")
    }

    /** Returns a violation description, or null if the UPDATE statement is allowed. */
    private fun checkUpdate(dao: Class<*>, sql: String): String? {
        val match = UPDATE_SQL.matchEntire(sql) ?: return "unrecognised UPDATE form: $sql"
        val table = match.groupValues[1]
        val columns = match.groupValues[2].split(',').map { it.substringBefore('=').trim().trim('`') }.toSet()
        return when (table) {
            "raw_captures" ->
                if (columns.isNotEmpty() && RAW_CAPTURE_UPDATABLE.containsAll(columns)) null
                else "UPDATE raw_captures may set only $RAW_CAPTURE_UPDATABLE, sets $columns"
            "activity_occurrences" -> {
                if (dao != LedgerWriteDao::class.java) return "UPDATE activity_occurrences outside LedgerWriteDao"
                val bad = columns intersect OCCURRENCE_IMMUTABLE
                if (bad.isEmpty()) null else "UPDATE activity_occurrences sets immutable columns $bad"
            }
            else -> "UPDATE of table $table is not allowed"
        }
    }

    internal companion object {
        const val DAO = "Landroidx/room/Dao;"
        const val QUERY = "Landroidx/room/Query;"
        const val INSERT = "Landroidx/room/Insert;"
        const val UPDATE = "Landroidx/room/Update;"
        const val UPSERT = "Landroidx/room/Upsert;"
        const val DELETE = "Landroidx/room/Delete;"
        const val RAW_QUERY = "Landroidx/room/RawQuery;"

        // UPDATE <table> SET <assignments> WHERE <condition>; any "OR <conflict>" clause fails to match.
        private val UPDATE_SQL = Regex("^UPDATE `?(\\w+)`? SET (.+?) WHERE .+$", RegexOption.IGNORE_CASE)
        private val RAW_CAPTURE_UPDATABLE = setOf("processing_state", "updated_at")
        private val OCCURRENCE_IMMUTABLE = setOf("id", "raw_capture_id", "captured_at", "created_at")

        /** Type descriptors of entities only LedgerWriteDao may insert (matched inside descriptor/signature). */
        private val LEDGER_ONLY_ENTITIES = listOf(
            "L" + ActivityOccurrenceEntity::class.java.name.replace('.', '/') + ";",
            "L" + CorrectionEntity::class.java.name.replace('.', '/') + ";",
        )

        /** The SQL of the @Query on [methodName] of [dao], as written in the source. */
        fun querySql(dao: Class<*>, methodName: String): String =
            ClassFileAnnotations.read(dao).methods
                .single { it.name == methodName }
                .annotations.single { it.descriptor == QUERY }
                .values["value"] as String
    }
}
