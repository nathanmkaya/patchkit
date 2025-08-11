package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.crypto.sha256Hex
import dev.nathanmkaya.patchkit.model.Patch

/**
 * If metadata.sha256 is present, verify it against the raw patch bytes.
 */
class HashValidator : PatchValidator {
    override suspend fun validate(
        patch: Patch,
        rawBytes: ByteArray?,
    ): ValidationResult {
        val expected = patch.metadata["sha256"] ?: return ValidationResult.Success
        if (rawBytes == null) {
            return ValidationResult.Failure(
                code = "HASH_MISSING_BYTES",
                message = "Cannot verify hash without raw bytes",
            )
        }
        val actual = sha256Hex(rawBytes)
        return if (actual.equals(expected, ignoreCase = true)) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(
                code = "HASH_MISMATCH",
                message = "SHA-256 mismatch: expected $expected, got $actual",
            )
        }
    }
}
