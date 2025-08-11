package dev.nathanmkaya.patchkit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Actions are polymorphic. The Json discriminator is "type".
 */
@Serializable
sealed interface Action {
    val description: String?
}

@Serializable
@SerialName("SqlAction")
data class SqlAction(
    val sql: String,
    override val description: String? = null,
) : Action

@Serializable
@SerialName("ParameterizedSqlAction")
data class ParameterizedSqlAction(
    val sql: String,
    val parameters: List<SqlArg>, // Strongly-typed parameters
    override val description: String? = null,
) : Action
