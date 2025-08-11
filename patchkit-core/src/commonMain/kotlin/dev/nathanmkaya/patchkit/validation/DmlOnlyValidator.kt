package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction

/**
 * Rejects CREATE / DROP / ALTER / TRUNCATE at the beginning of the statement.
 * (SQLite doesn't have TRUNCATE, but we keep it defensive.)
 */
class DmlOnlyValidator : PatchValidator {
    private val ddlKeywords = setOf("CREATE", "DROP", "ALTER", "TRUNCATE")

    override suspend fun validate(
        patch: Patch,
        rawBytes: ByteArray?,
    ): ValidationResult {
        for (action in patch.actions) {
            val sql =
                when (action) {
                    is SqlAction -> action.sql
                    is ParameterizedSqlAction -> action.sql
                }
            val normalized = sql.trimStart().uppercase()
            if (ddlKeywords.any { normalized.startsWith(it) }) {
                return ValidationResult.Failure(
                    code = "DDL_NOT_ALLOWED",
                    message = "DDL statement not allowed: ${sql.take(50)}...",
                )
            }
        }
        return ValidationResult.Success
    }
}
