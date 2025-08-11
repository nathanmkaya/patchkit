package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.Patch

/** Contract for all validators. */
fun interface PatchValidator {
    suspend fun validate(
        patch: Patch,
        rawBytes: ByteArray?,
    ): ValidationResult
}

/** Result type for validators. */
sealed interface ValidationResult {
    data object Success : ValidationResult

    data class Failure(
        val code: String,
        val message: String,
    ) : ValidationResult
}
