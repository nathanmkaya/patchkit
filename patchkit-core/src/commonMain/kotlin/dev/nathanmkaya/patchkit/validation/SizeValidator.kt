package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.Patch

class SizeValidator(
    private val maxBytes: Int = 512_000,
    private val maxActions: Int = 200,
) : PatchValidator {
    override suspend fun validate(
        patch: Patch,
        rawBytes: ByteArray?,
    ): ValidationResult {
        if (rawBytes != null && rawBytes.size > maxBytes) {
            return ValidationResult.Failure(
                code = "SIZE_EXCEEDED",
                message = "Patch size ${rawBytes.size} exceeds max $maxBytes",
            )
        }
        if (patch.actions.size > maxActions) {
            return ValidationResult.Failure(
                code = "TOO_MANY_ACTIONS",
                message = "Action count ${patch.actions.size} exceeds max $maxActions",
            )
        }
        return ValidationResult.Success
    }
}
