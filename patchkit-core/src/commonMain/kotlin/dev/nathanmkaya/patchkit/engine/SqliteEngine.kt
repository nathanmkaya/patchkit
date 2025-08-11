package dev.nathanmkaya.patchkit.engine

import dev.nathanmkaya.patchkit.model.SqlArg

/**
 * Minimal, ORM-agnostic surface to run scalar queries and DML.
 *
 * Contract:
 * - queryScalar: return the first column of the first row, or Null/ null when no rows.
 * - execute: return affected rows (SQLite `changes()` semantics).
 */
interface SqliteEngine {
    suspend fun queryScalar(
        sql: String,
        args: List<SqlArg> = emptyList(),
    ): SqlScalar?

    suspend fun execute(
        sql: String,
        args: List<SqlArg> = emptyList(),
    ): Int
}

/**
 * Transaction management boundary.
 * - If immediate=true, use BEGIN IMMEDIATE; else use deferred BEGIN.
 * - Must COMMIT on success and ROLLBACK on exception.
 */
interface TransactionManager {
    suspend fun <T> inTransaction(
        immediate: Boolean = true,
        block: suspend () -> T,
    ): T
}

/** Combined capability required by PatchKit. */
interface TransactionalEngine :
    SqliteEngine,
    TransactionManager

/** Factory so callers can resolve a target DB lazily. */
fun interface EngineProvider {
    suspend fun provide(): TransactionalEngine
}
