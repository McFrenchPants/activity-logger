package com.mcfrenchpants.activityledger.core.data.db.migration

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabaseFactory
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards against destructive migration fallback: a database with no migration
 * path to the current version must fail to open, and its data must stay on disk.
 */
@RunWith(AndroidJUnit4::class)
class DestructiveMigrationGuardTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun deleteBefore() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun deleteAfter() {
        context.deleteDatabase(TEST_DB)
    }

    /**
     * Construction: a real current-version file (built by the production factory)
     * holding a marker row, whose `user_version` is then raised to CURRENT + 1.
     * Opening it through the production configuration is a downgrade with no
     * migration path, which reaches Room's "migration required" decision. Any
     * destructive fallback (fallbackToDestructiveMigration or
     * ...OnDowngrade) would wipe the tables and open successfully instead.
     */
    @Test
    fun openingWithoutMigrationPathFailsAndKeepsData() {
        val current = MigrationHarness.CURRENT_VERSION
        val onDisk = current + 1

        val seedDb = ActivityLedgerDatabaseFactory.builder(context, TEST_DB).allowMainThreadQueries().build()
        try {
            seedDb.rawCaptureDao().insert(marker())
        } finally {
            seedDb.close()
        }
        val path = context.getDatabasePath(TEST_DB).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = onDisk }

        val db = ActivityLedgerDatabaseFactory.builder(context, TEST_DB).allowMainThreadQueries().build()
        try {
            val error = assertFailsWith<IllegalStateException>("opening must fail without a migration path") {
                db.openHelper.writableDatabase
            }
            assertTrue(
                error.message.orEmpty().contains("A migration from $onDisk to $current was required but not found"),
                "unexpected error: ${error.message}",
            )
        } finally {
            db.close()
        }

        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(onDisk, raw.version, "user_version must be untouched")
            raw.rawQuery("SELECT raw_text FROM raw_captures WHERE id = ?", arrayOf(MARKER_ID)).use { c ->
                assertTrue(c.moveToFirst(), "marker row was destroyed")
                assertEquals(MARKER_TEXT, c.getString(0))
            }
        }
    }

    /** Complementary text scan: no destructive fallback call anywhere in production sources. */
    @Test
    fun productionSourcesNeverConfigureDestructiveFallback() {
        val mainDir = listOf(File("src/main"), File("core-data/src/main")).firstOrNull { it.isDirectory }
            ?: error("core-data/src/main not found from working dir ${File("").absolutePath}")
        val sources = mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue(
            sources.any { it.name == "ActivityLedgerDatabaseFactory.kt" },
            "scan did not see the factory source (scanned ${sources.size} files under $mainDir)",
        )
        val offenders = sources.filter { it.readText().contains("fallbackToDestructiveMigration") }.map { it.path }
        assertEquals(emptyList(), offenders, "destructive migration fallback configured in production sources")
    }

    private fun marker() = RawCaptureEntity(
        id = MARKER_ID,
        source = CaptureSource.PHONE_TEXT,
        sourceSurface = null,
        capturedAt = 1L,
        capturedZoneId = "UTC",
        rawText = MARKER_TEXT,
        speechConfidence = null,
        speechAlternativesJson = null,
        processingState = ProcessingState.CAPTURED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        const val TEST_DB = "activity-ledger-destructive-guard"
        const val MARKER_ID = "00000000-0000-7000-8000-000000009999"
        const val MARKER_TEXT = "marker row"
    }
}
