package dev.nathanmkaya.patchkit.idempotency

import dev.nathanmkaya.patchkit.engine.SqlScalar
import dev.nathanmkaya.patchkit.engine.SqliteEngine
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Default SQL-backed idempotency using a simple table.
 * Safe to use with SQLite; CREATE statements are executed outside the mutating tx.
 */
@OptIn(ExperimentalTime::class)
class TableBasedIdempotency(
    private val tableName: String = "_patchkit_applied",
) : IdempotencyManager {
    override suspend fun initialize(engine: SqliteEngine) {
        engine.execute(
            """
            CREATE TABLE IF NOT EXISTS $tableName (
                patch_id  TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL,
                metadata  TEXT
            )
            """.trimIndent(),
        )
        engine.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_${tableName}_patch_id 
            ON $tableName(patch_id)
            """.trimIndent(),
        )
    }

    override suspend fun hasBeenApplied(
        patchId: String,
        engine: SqliteEngine,
    ): Boolean {
        val scalar =
            engine.queryScalar(
                "SELECT COUNT(*) FROM $tableName WHERE patch_id = ?",
                listOf(SqlArg.Text(patchId)),
            )
        val count =
            when (scalar) {
                is SqlScalar.Int64 -> scalar.v
                is SqlScalar.Real -> scalar.v.toLong()
                is SqlScalar.Text -> scalar.v.toLongOrNull() ?: 0L
                else -> 0L
            }
        return count > 0
    }

    override suspend fun recordApplication(
        patchId: String,
        engine: SqliteEngine,
        metadata: String?,
    ) {
        engine.execute(
            "INSERT INTO $tableName (patch_id, applied_at, metadata) VALUES (?, ?, ?)",
            listOf(
                SqlArg.Text(patchId),
                SqlArg.Int64(Clock.System.now().toEpochMilliseconds()),
                metadata?.let { SqlArg.Text(it) } ?: SqlArg.Null,
            ),
        )
    }
}
