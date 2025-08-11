package dev.nathanmkaya.patchkit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base interface for SQL actions that can be executed within a patch.
 *
 * Actions are polymorphic and serialized with a "type" discriminator in JSON.
 * All actions are executed within a single ACID transaction, ensuring atomicity.
 */
@Serializable
sealed interface Action {
    /** Human-readable description of what this action does */
    val description: String?
}

/**
 * A direct SQL action that executes raw SQL statements.
 *
 * This action type executes SQL directly without parameter binding. Use with caution
 * in production environments, as it doesn't provide protection against SQL injection
 * if user input is involved. Prefer [ParameterizedSqlAction] for better security.
 *
 * ## Example JSON:
 * ```json
 * {
 *   "type": "SqlAction",
 *   "sql": "UPDATE users SET active = 1 WHERE created_at > '2024-01-01'",
 *   "description": "Activate users created in 2024"
 * }
 * ```
 *
 * @param sql The SQL statement to execute
 * @param description Optional human-readable description of this action
 */
@Serializable
@SerialName("SqlAction")
data class SqlAction(
    val sql: String,
    override val description: String? = null,
) : Action

/**
 * A parameterized SQL action that uses strongly-typed parameters for safe execution.
 *
 * This action type uses parameter placeholders (?) in SQL with strongly-typed arguments,
 * providing protection against SQL injection and ensuring type safety across platforms.
 * This is the recommended action type for production use.
 *
 * ## Example JSON:
 * ```json
 * {
 *   "type": "ParameterizedSqlAction",
 *   "sql": "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
 *   "parameters": [
 *     { "type": "Text", "v": "John Doe" },
 *     { "type": "Text", "v": "john@example.com" },
 *     { "type": "Int64", "v": 30 }
 *   ],
 *   "description": "Add new user record"
 * }
 * ```
 *
 * @param sql The SQL statement with parameter placeholders (?)
 * @param parameters List of strongly-typed parameters matching the placeholders
 * @param description Optional human-readable description of this action
 */
@Serializable
@SerialName("ParameterizedSqlAction")
data class ParameterizedSqlAction(
    val sql: String,
    val parameters: List<SqlArg>,
    override val description: String? = null,
) : Action
