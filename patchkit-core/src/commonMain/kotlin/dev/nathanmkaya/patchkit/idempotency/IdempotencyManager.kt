package dev.nathanmkaya.patchkit.idempotency

import dev.nathanmkaya.patchkit.engine.SqliteEngine

/**
 * Interface for managing patch idempotency to prevent duplicate applications.
 *
 * Idempotency management ensures that patches are applied exactly once, even if
 * the same patch is submitted multiple times. This is critical for maintaining
 * data consistency and preventing unintended side effects from repeated operations.
 *
 * ## Implementation Strategies:
 * - **Table-based**: Store applied patch IDs in a dedicated database table
 * - **File-based**: Maintain a local file with applied patch records
 * - **External cache**: Use Redis or similar external storage
 * - **Custom**: Implement domain-specific tracking mechanisms
 *
 * ## Lifecycle:
 * 1. **initialize()**: Set up any required storage structures
 * 2. **hasBeenApplied()**: Check if patch was previously applied successfully
 * 3. **recordApplication()**: Mark patch as successfully applied after commit
 *
 * ## Usage:
 * ```kotlin
 * class CustomIdempotencyManager : IdempotencyManager {
 *     override suspend fun initialize(engine: SqliteEngine) {
 *         engine.execute("CREATE TABLE IF NOT EXISTS applied_patches (patch_id TEXT PRIMARY KEY)")
 *     }
 *     
 *     override suspend fun hasBeenApplied(patchId: String, engine: SqliteEngine): Boolean {
 *         val count = engine.queryScalar("SELECT COUNT(*) FROM applied_patches WHERE patch_id = ?", 
 *                                       listOf(SqlArg.Text(patchId)))
 *         return count?.let { it.asLongOrZero() > 0 } ?: false
 *     }
 *     
 *     override suspend fun recordApplication(patchId: String, engine: SqliteEngine, metadata: String?) {
 *         engine.execute("INSERT INTO applied_patches (patch_id) VALUES (?)", 
 *                       listOf(SqlArg.Text(patchId)))
 *     }
 * }
 * ```
 */
interface IdempotencyManager {
    /**
     * Initialize any required storage structures for tracking applied patches.
     *
     * This method is called before checking or recording patch applications.
     * It should create any necessary tables, indexes, or other storage structures.
     * Must be safe to call multiple times (should not fail if structures already exist).
     *
     * @param engine Database engine for storage operations
     */
    suspend fun initialize(engine: SqliteEngine)

    /**
     * Check if a patch has already been successfully applied.
     *
     * This check happens before patch execution to determine if the patch
     * should be skipped. Only patches that completed successfully should be
     * considered "applied" - failed attempts should not prevent retry.
     *
     * @param patchId Unique identifier of the patch to check
     * @param engine Database engine for querying application records
     * @return true if the patch was already applied successfully, false otherwise
     */
    suspend fun hasBeenApplied(
        patchId: String,
        engine: SqliteEngine,
    ): Boolean

    /**
     * Record that a patch was successfully applied.
     *
     * This method is called only after successful patch execution and transaction
     * commit. It marks the patch as applied to prevent future duplicate execution.
     *
     * @param patchId Unique identifier of the successfully applied patch
     * @param engine Database engine for recording the application
     * @param metadata Optional metadata about the application (e.g., timestamp, user, environment)
     */
    suspend fun recordApplication(
        patchId: String,
        engine: SqliteEngine,
        metadata: String? = null,
    )
}
