package com.mcfrenchpants.activityledger.core.data.id

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Production [IdFactory]: time-ordered UUIDv7 from the Kotlin standard library
 * (kotlin.uuid.Uuid.generateV7, experimental as of Kotlin 2.3.21).
 *
 * This is the ONLY file in the module allowed to opt in to ExperimentalUuidApi;
 * everything else sees plain String IDs. See
 * docs/proposals/room-schema/TOOLING_NOTES.md for the evidence that V7 works on
 * this toolchain.
 */
@OptIn(ExperimentalUuidApi::class)
internal object UuidV7IdFactory : IdFactory {
    override fun newId(): String = Uuid.generateV7().toString()
}
