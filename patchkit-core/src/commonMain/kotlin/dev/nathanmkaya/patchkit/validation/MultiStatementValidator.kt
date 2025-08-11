package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction

/**
 * Enforces the single-statement rule:
 * - Rejects any top-level semicolon that is not the single trailing semicolon.
 * - Allows one trailing semicolon.
 * - Ignores semicolons inside quotes.
 */
class MultiStatementValidator : PatchValidator {
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
            if (hasDisallowedSemicolon(sql)) {
                return ValidationResult.Failure(
                    code = "MULTI_STATEMENT",
                    message = "Multiple statements not allowed: ${sql.take(50)}...",
                )
            }
        }
        return ValidationResult.Success
    }

    private fun hasDisallowedSemicolon(sql: String): Boolean {
        val trimmed = sql.trimEnd()
        val endsWithSemicolon = trimmed.endsWith(';')
        val withoutTrailing = if (endsWithSemicolon) trimmed.dropLast(1) else trimmed
        val hasTopLevelBeforeEnd = containsTopLevelSemicolon(withoutTrailing)
        return if (endsWithSemicolon) {
            // Permit exactly one trailing semicolon, but none before it.
            hasTopLevelBeforeEnd
        } else {
            // No trailing semicolon: forbid any top-level semicolon.
            containsTopLevelSemicolon(trimmed)
        }
    }

    private fun containsTopLevelSemicolon(sql: String): Boolean {
        var inSingle = false
        var inDouble = false
        var escaped = false

        for (ch in sql) {
            when {
                escaped -> {
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '\"' && !inSingle -> inDouble = !inDouble
                ch == ';' && !inSingle && !inDouble -> return true
            }
        }
        return false
    }
}
