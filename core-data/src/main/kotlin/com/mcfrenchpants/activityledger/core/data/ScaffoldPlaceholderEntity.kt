package com.mcfrenchpants.activityledger.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SCAFFOLD PLACEHOLDER ENTITY -- no product meaning.
 *
 * This table models nothing in the product domain. It exists only so that KSP has
 * a Room entity to process, proving the annotation processor runs and that a
 * schema JSON is exported. The real schema (raw captures, canonical activities,
 * aliases, interpretations, occurrences, corrections) is a separate backlog item
 * and is deliberately NOT guessed at here.
 *
 * Delete this file -- and its @Dao and @Database -- when the real schema lands.
 */
@Entity(tableName = "scaffold_placeholder")
internal data class ScaffoldPlaceholderEntity(
    @PrimaryKey val id: Long,
    val placeholder: String,
)
