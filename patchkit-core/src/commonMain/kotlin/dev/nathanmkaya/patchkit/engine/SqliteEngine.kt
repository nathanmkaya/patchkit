package dev.nathanmkaya.patchkit.engine

import dev.nathanmkaya.patchkit.model.SqlArg

/**
 * Core database interface providing minimal SQL execution capabilities.
 *
 * This interface abstracts database operations to support different SQLite drivers
 * and implementations while maintaining consistent behavior across platforms.
 * It focuses on the essential operations needed for patch execution.
 *
 * ## Implementation Contract:
 * - **queryScalar**: Return the first column of the first row, or null when no rows
 * - **execute**: Return affected rows using SQLite `changes()` semantics
 * - All operations should support parameterized queries for safety
 */
interface SqliteEngine {
    /**
     * Execute a query and return the first column of the first row as a scalar value.
     *
     * Used primarily for condition evaluation in preconditions and postconditions.
     * Returns null if no rows are returned by the query.
     *
     * @param sql SQL query statement
     * @param args Parameterized arguments for the query
     * @return First column of first row, or null if no results
     */
    suspend fun queryScalar(
        sql: String,
        args: List<SqlArg> = emptyList(),
    ): SqlScalar?

    /**
     * Execute a DML statement and return the number of affected rows.
     *
     * Used for INSERT, UPDATE, DELETE operations within patch actions.
     * Returns the number of rows modified, following SQLite's `changes()` behavior.
     *
     * @param sql SQL DML statement
     * @param args Parameterized arguments for the statement
     * @return Number of rows affected by the operation
     */
    suspend fun execute(
        sql: String,
        args: List<SqlArg> = emptyList(),
    ): Int
}

/**
 * Interface for managing database transaction boundaries.
 *
 * Provides ACID transaction management with support for both immediate and deferred
 * transaction modes. All implementations must ensure proper COMMIT/ROLLBACK handling.
 */
interface TransactionManager {
    /**
     * Execute a block of operations within a database transaction.
     *
     * The transaction automatically commits on successful completion and rolls back
     * if any exception is thrown during execution.
     *
     * @param immediate If true, use BEGIN IMMEDIATE for write lock; if false, use deferred BEGIN
     * @param block Suspending block of operations to execute within the transaction
     * @return Result of the block execution
     * @throws Exception Any exception thrown by the block, after transaction rollback
     */
    suspend fun <T> inTransaction(
        immediate: Boolean = true,
        block: suspend () -> T,
    ): T
}

/**
 * Combined interface providing both SQL execution and transaction management.
 *
 * This is the primary interface required by PatchKit for complete database operations.
 * It combines basic SQL execution with transaction management capabilities.
 */
interface TransactionalEngine :
    SqliteEngine,
    TransactionManager

/**
 * Factory interface for lazily providing database engines.
 *
 * Allows PatchKit to resolve database connections on-demand rather than requiring
 * pre-initialized connections. This supports use cases where databases may not
 * be immediately available or need per-operation setup.
 */
fun interface EngineProvider {
    /**
     * Provide a database engine instance for patch execution.
     *
     * @return TransactionalEngine instance ready for use
     */
    suspend fun provide(): TransactionalEngine
}
