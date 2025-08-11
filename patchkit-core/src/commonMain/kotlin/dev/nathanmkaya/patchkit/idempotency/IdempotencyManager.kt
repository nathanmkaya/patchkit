package dev.nathanmkaya.patchkit.idempotency

import dev.nathanmkaya.patchkit.engine.SqliteEngine

interface IdempotencyManager {
    /** Ensure any storage exists (tables/indexes or caches). Safe to call multiple times. */
    suspend fun initialize(engine: SqliteEngine)

    /** True if the given patchId was already applied. */
    suspend fun hasBeenApplied(
        patchId: String,
        engine: SqliteEngine,
    ): Boolean

    /** Record a successful application of patchId (after commit). */
    suspend fun recordApplication(
        patchId: String,
        engine: SqliteEngine,
        metadata: String? = null,
    )
}
