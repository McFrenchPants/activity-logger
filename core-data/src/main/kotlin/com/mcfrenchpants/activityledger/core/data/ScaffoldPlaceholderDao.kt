package com.mcfrenchpants.activityledger.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * SCAFFOLD PLACEHOLDER DAO -- no product meaning.
 *
 * Exists only to give Room's compiler something to generate an implementation
 * for. It is not a repository, not an API, and models no product behaviour.
 * Delete it together with [ScaffoldPlaceholderEntity] when the real schema lands.
 */
@Dao
internal interface ScaffoldPlaceholderDao {
    @Query("SELECT COUNT(*) FROM scaffold_placeholder")
    suspend fun count(): Int

    @Insert
    suspend fun insert(row: ScaffoldPlaceholderEntity)
}
