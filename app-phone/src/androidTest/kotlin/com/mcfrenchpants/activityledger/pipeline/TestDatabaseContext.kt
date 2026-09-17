package com.mcfrenchpants.activityledger.pipeline

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Prefix every database file this context opens gets, keeping test data out of the real one. */
private const val TEST_DATABASE_PREFIX = "instrumentation_test_"

/**
 * A context that redirects every database file name through [TEST_DATABASE_PREFIX].
 *
 * The production factory decides the database file name itself, so an on-device test that used
 * the plain application context would open -- and write into -- the very file a real user's
 * history lives in. This wrapper makes that impossible: `activity_ledger.db` becomes
 * `instrumentation_test_activity_ledger.db` in the same directory, a file no production code
 * path ever names.
 *
 * [getApplicationContext] deliberately returns this wrapper rather than the real application:
 * the persistence layer takes the application context before opening anything, so a wrapper
 * that handed back the real one would be bypassed entirely.
 */
class TestDatabaseContext(base: Context) : ContextWrapper(base) {

    /** The name the test's own database ends up under, for asserting on and deleting. */
    fun testNameOf(name: String): String = TEST_DATABASE_PREFIX + name

    /**
     * Every file this context has created in the app's databases directory (the database plus
     * any -wal/-shm sidecars). Nothing else in that directory can match the prefix.
     */
    fun testDatabaseFiles(): List<File> {
        val directory = super.getDatabasePath(TEST_DATABASE_PREFIX).parentFile ?: return emptyList()
        return directory.listFiles { file -> file.name.startsWith(TEST_DATABASE_PREFIX) }?.toList().orEmpty()
    }

    /** Deletes everything [testDatabaseFiles] lists. Never touches a production database file. */
    fun clearTestDatabases() {
        testDatabaseFiles().forEach { it.delete() }
    }

    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File = super.getDatabasePath(testNameOf(name))

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = super.openOrCreateDatabase(testNameOf(name), mode, factory)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase = super.openOrCreateDatabase(testNameOf(name), mode, factory, errorHandler)

    override fun deleteDatabase(name: String): Boolean = super.deleteDatabase(testNameOf(name))
}
