package dev.nathanmkaya.patchkit.model

import kotlinx.serialization.Serializable

/**
 * Public Patch format (v1).
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
 * Numeric condition checks in MVP.
 */
@Serializable
data class Condition(
    val sql: String,
    val operator: ComparisonOperator = ComparisonOperator.EQUALS,
    val expected: Long,
    val description: String? = null,
)

@Serializable
enum class ComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    ;

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
