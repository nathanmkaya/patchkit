package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.model.Patch

/**
 * Interface for validating patches before execution.
 *
 * Validators form a validation chain that checks patches for security, integrity,
 * and policy compliance before they are executed. Each validator can reject a patch
 * by returning a ValidationResult.Failure with an appropriate error code and message.
 *
 * Validators run in the order they are registered and the first failure stops
 * the validation chain, preventing patch execution.
 *
 * ## Built-in Validators:
 * - **SizeValidator**: Enforces maximum patch size and action count limits
 * - **HashValidator**: Verifies SHA-256 hash in patch metadata for integrity
 * - **DmlOnlyValidator**: Blocks DDL operations when allowDDL is false
 * - **MultiStatementValidator**: Prevents multiple SQL statements per action
 *
 * ## Custom Validators:
 * ```kotlin
 * class BusinessRuleValidator : PatchValidator {
 *     override suspend fun validate(patch: Patch, rawBytes: ByteArray?): ValidationResult {
 *         return if (meetsBusinessRules(patch)) {
 *             ValidationResult.Success
 *         } else {
 *             ValidationResult.Failure("BUSINESS_RULE", "Violates business constraints")
 *         }
 *     }
 * }
 * ```
 */
fun interface PatchValidator {
    /**
     * Validate a patch for execution.
     *
     * @param patch The parsed patch object to validate
     * @param rawBytes The original patch bytes, useful for hash validation or size checks
     * @return ValidationResult.Success if valid, ValidationResult.Failure with details if invalid
     */
    suspend fun validate(
        patch: Patch,
        rawBytes: ByteArray?,
    ): ValidationResult
}

/**
 * Result of patch validation.
 *
 * Validators return either Success to allow execution to proceed, or Failure
 * with a machine-readable error code and human-readable message to prevent execution.
 */
sealed interface ValidationResult {
    /** Indicates the patch passed validation and can proceed to execution */
    data object Success : ValidationResult

    /**
     * Indicates the patch failed validation and should not be executed.
     *
     * @param code Machine-readable error code for programmatic handling
     * @param message Human-readable error message for logging/display
     */
    data class Failure(
        val code: String,
        val message: String,
    ) : ValidationResult
}
