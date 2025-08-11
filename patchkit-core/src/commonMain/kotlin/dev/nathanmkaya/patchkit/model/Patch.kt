package dev.nathanmkaya.patchkit.model

import kotlinx.serialization.Serializable

/**
 * A database patch containing SQL operations with validation and safety checks.
 *
 * Patches are JSON-serializable units of database changes that execute within ACID transactions.
 * They support preconditions for safety, actions for the actual changes, and postconditions
 * for validation. All operations are executed atomically - either all succeed or all rollback.
 *
 * ## Example JSON:
 * ```json
 * {
 *   "version": 1,
 *   "id": "add-user-table-v1",
 *   "target": "main",
 *   "description": "Create users table with constraints",
 *   "preconditions": [
 *     {
 *       "sql": "SELECT COUNT(*) FROM sqlite_master WHERE name='users'",
 *       "operator": "EQUALS",
 *       "expected": 0,
 *       "description": "Users table should not exist"
 *     }
 *   ],
 *   "actions": [
 *     {
 *       "type": "SqlAction",
 *       "sql": "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)",
 *       "description": "Create users table"
 *     }
 *   ],
 *   "postconditions": [
 *     {
 *       "sql": "SELECT COUNT(*) FROM sqlite_master WHERE name='users'",
 *       "operator": "EQUALS",
 *       "expected": 1,
 *       "description": "Users table should exist"
 *     }
 *   ],
 *   "metadata": {
 *     "author": "DevOps Team",
 *     "sha256": "abc123..."
 *   }
 * }
 * ```
 *
 * @param version Format version, currently must be 1
 * @param id Unique identifier for this patch, used for idempotency tracking
 * @param target Database target alias from the engine registry
 * @param description Human-readable description of what this patch does
 * @param preconditions Conditions that must be met before executing actions
 * @param actions SQL operations to execute within a single transaction
 * @param postconditions Conditions that must be met after executing actions
 * @param metadata Additional metadata including optional SHA-256 hash for integrity verification
 */
@Serializable
data class Patch(
    val version: Int = 1,
    val id: String,
    val target: String,
    val description: String? = null,
    val preconditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
    val postconditions: List<Condition> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(version == 1) { "Only version 1 supported in MVP" }
        require(id.isNotBlank()) { "Patch ID cannot be blank" }
        require(target.isNotBlank()) { "Target alias cannot be blank" }
    }
}

/**
 * A condition that must be satisfied for patch execution to proceed.
 *
 * Conditions are SQL queries that return a single numeric value, which is then compared
 * against an expected value using the specified operator. They are used for both
 * preconditions (checked before execution) and postconditions (checked after execution).
 *
 * @param sql SQL query that must return exactly one row with one numeric column
 * @param operator Comparison operator to use for evaluation
 * @param expected Expected numeric value to compare against the SQL result
 * @param description Human-readable description of what this condition verifies
 */
@Serializable
data class Condition(
    val sql: String,
    val operator: ComparisonOperator = ComparisonOperator.EQUALS,
    val expected: Long,
    val description: String? = null,
)

/**
 * Comparison operators for evaluating condition results.
 *
 * Used to compare the actual result from a condition's SQL query against the expected value.
 */
@Serializable
enum class ComparisonOperator {
    /** Actual value must equal expected value */
    EQUALS,
    /** Actual value must not equal expected value */
    NOT_EQUALS,
    /** Actual value must be greater than expected value */
    GREATER_THAN,
    /** Actual value must be greater than or equal to expected value */
    GREATER_OR_EQUAL,
    /** Actual value must be less than expected value */
    LESS_THAN,
    /** Actual value must be less than or equal to expected value */
    LESS_OR_EQUAL,
    ;

    /**
     * Evaluate whether the condition passes given actual and expected values.
     *
     * @param actual The value returned by the condition's SQL query
     * @param expected The expected value from the condition
     * @return true if the condition passes, false if it fails
     */
    fun evaluate(
        actual: Long,
        expected: Long,
    ): Boolean =
        when (this) {
            EQUALS -> actual == expected
            NOT_EQUALS -> actual != expected
            GREATER_THAN -> actual > expected
            GREATER_OR_EQUAL -> actual >= expected
            LESS_THAN -> actual < expected
            LESS_OR_EQUAL -> actual <= expected
        }
}
