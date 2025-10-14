package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction

/** Rejects actions whose first significant keyword is SELECT. */
class SelectActionValidator : PatchValidator {
    override suspend fun validate(patch: Patch, rawBytes: ByteArray?): ValidationResult {
        for (action in patch.actions) {
            val sql =
                when (action) {
                    is SqlAction -> action.sql
                    is ParameterizedSqlAction -> action.sql
                }
            val keyword = firstKeyword(sql)
            if (keyword == "SELECT") {
                return ValidationResult.Failure(
                    code = "SELECT_NOT_ALLOWED",
                    message = "SELECT statements are not allowed as patch actions",
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
                sql.startsWith("--", index) ->
                    index = sql.indexOf('\n', index).takeIf { it >= 0 }?.plus(1) ?: length
                sql.startsWith("/*", index) ->
                    index = sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: length
                sql[index].isWhitespace() -> index++
                else -> {
                    var end = index
                    while (end < length && !sql[end].isWhitespace()) end++
                    return sql.substring(index, end).uppercase()
                }
            }
        }
        return null
    }
}
