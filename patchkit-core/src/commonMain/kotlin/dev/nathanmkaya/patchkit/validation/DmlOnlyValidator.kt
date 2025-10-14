package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction

/**
 * Enforces a positive allowlist of DML operations.
 *
 * The statement's first significant keyword (after stripping comments/whitespace)
 * must be one of the allowed verbs. PRAGMA statements are only accepted when they
 * target a known-safe pragma.
 */
class DmlOnlyValidator : PatchValidator {
    private val allowedVerbs = setOf("INSERT", "UPDATE", "DELETE", "REPLACE", "WITH")
    private val allowedPragmas = setOf("foreign_keys", "defer_foreign_keys", "busy_timeout")

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
            val keyword = firstKeyword(sql) ?: continue
            if (keyword == "PRAGMA") {
                val pragma = extractPragmaName(sql)
                if (pragma == null || pragma !in allowedPragmas) {
                    return ValidationResult.Failure(
                        code = "PRAGMA_NOT_ALLOWED",
                        message = "PRAGMA not allowed: ${pragma ?: sql.take(30)}",
                    )
                }
                continue
            }
            if (keyword !in allowedVerbs) {
                return ValidationResult.Failure(
                    code = "${keyword}_NOT_ALLOWED",
                    message = "Statement not allowed: ${sql.take(50)}...",
                )
            }
        }
        return ValidationResult.Success
    }

    private fun firstKeyword(sql: String): String? {
        var index = 0
        val length = sql.length
        while (index < length) {
            when {
                sql.startsWith("--", index) -> {
                    index = sql.indexOf('\n', index).takeIf { it >= 0 }?.plus(1) ?: length
                }
                sql.startsWith("/*", index) -> {
                    index = sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: length
                }
                sql[index].isWhitespace() -> {
                    index++
                }
                else -> {
                    val end = sql.indexOfFirstWhitespace(index)
                    val keyword = sql.substring(index, end).uppercase()
                    return keyword
                }
            }
        }
        return null
    }

    private fun extractPragmaName(sql: String): String? {
        val pragmaIndex = sql.indexOf("PRAGMA", ignoreCase = true)
        if (pragmaIndex < 0) return null
        var index = pragmaIndex + 6
        while (index < sql.length && sql[index].isWhitespace()) index++
        val end = sql.indexOfFirstDelimiter(index)
        if (end <= index) return null
        return sql.substring(index, end).trim().lowercase()
    }

    private fun String.indexOfFirstWhitespace(start: Int): Int {
        var i = start
        while (i < length && !this[i].isWhitespace()) i++
        return i
    }

    private fun String.indexOfFirstDelimiter(start: Int): Int {
        var i = start
        while (i < length) {
            val ch = this[i]
            if (ch.isWhitespace() || ch == ';' || ch == '(') return i
            i++
        }
        return length
    }
}
