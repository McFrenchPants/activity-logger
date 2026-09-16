package com.mcfrenchpants.activityledger.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider

/**
 * The single way host-side tests open a Room database: in-memory, main-thread
 * queries allowed. It deliberately adds NO callback (in particular it does not
 * force PRAGMA foreign_keys): tests must exercise the same foreign-key
 * enforcement the production database gets from Room itself.
 */
internal inline fun <reified T : RoomDatabase> inMemoryTestDatabase(): T =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), T::class.java)
        .allowMainThreadQueries()
        .build()
