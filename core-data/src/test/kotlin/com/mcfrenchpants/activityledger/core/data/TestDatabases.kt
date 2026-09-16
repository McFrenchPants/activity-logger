package com.mcfrenchpants.activityledger.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider

/**
 * The single way host-side tests open a Room database: in-memory, with
 * PRAGMA foreign_keys = ON set on every open. Room only turns foreign keys on
 * itself when the schema declares one, so this makes enforcement explicit and
 * independent of the schema under test.
 */
internal inline fun <reified T : RoomDatabase> inMemoryTestDatabase(): T =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), T::class.java)
        .addCallback(ForeignKeysOnCallback)
        .allowMainThreadQueries()
        .build()

internal object ForeignKeysOnCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}
