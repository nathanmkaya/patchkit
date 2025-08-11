package dev.nathanmkaya.patchkit

import dev.nathanmkaya.patchkit.engine.EngineProvider
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.exec.ExecConfig
import dev.nathanmkaya.patchkit.exec.ExecutionEvent
import dev.nathanmkaya.patchkit.exec.ExecutionReport
import dev.nathanmkaya.patchkit.exec.PatchExecutor
import dev.nathanmkaya.patchkit.idempotency.IdempotencyManager
import dev.nathanmkaya.patchkit.idempotency.TableBasedIdempotency
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.validation.DmlOnlyValidator
import dev.nathanmkaya.patchkit.validation.HashValidator
import dev.nathanmkaya.patchkit.validation.MultiStatementValidator
import dev.nathanmkaya.patchkit.validation.PatchValidator
import dev.nathanmkaya.patchkit.validation.SizeValidator
import dev.nathanmkaya.patchkit.validation.ValidationResult
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Configuration for PatchKit behavior and security policies.
 *
 * @param allowDDL Whether to allow DDL operations (CREATE, ALTER, DROP). Default is false for security.
 * @param maxBytes Maximum patch size in bytes to prevent resource exhaustion. Default is 512KB.
 * @param maxActions Maximum number of actions per patch to limit transaction scope. Default is 200.
 * @param perActionTimeoutMs Timeout for individual action execution in milliseconds. Default is 10 seconds.
 * @param totalTimeoutMs Total timeout for entire patch execution in milliseconds. Default is 60 seconds.
 * @param verifyHash Whether to verify SHA-256 hash in patch metadata for integrity. Default is true.
 * @param checksInReadTx Whether to run pre/post conditions within read transactions. Default is false.
 * @param idempotency Manager for preventing duplicate patch application. Default uses table-based tracking.
 * @param json JSON configuration for patch deserialization. Default uses strict parsing.
 */
data class PatchKitConfig(
    val allowDDL: Boolean = false,
    val maxBytes: Int = 512_000,
    val maxActions: Int = 200,
    val perActionTimeoutMs: Long = 10_000,
    val totalTimeoutMs: Long = 60_000,
    val verifyHash: Boolean = true,
    val checksInReadTx: Boolean = false,
    val idempotency: IdempotencyManager? = TableBasedIdempotency(),
    val json: Json = PatchKitJson.strict,
)

/**
 * Main orchestrator for executing SQL database patches with comprehensive safety features.
 *
 * PatchKit provides a secure, reliable way to apply database schema and data migrations through
 * JSON-defined patches. It ensures data integrity through ACID transactions, comprehensive
 * validation, idempotency management, and detailed execution reporting.
 *
 * ## Key Features:
 * - **Transactional Safety**: All patches execute within ACID transactions with automatic rollback
 * - **Comprehensive Validation**: Multi-layered validation including size, hash, and DDL restrictions
 * - **Idempotency**: Prevents duplicate patch application through pluggable management
 * - **Security-First**: Configurable restrictions on dangerous SQL operations
 * - **Detailed Auditing**: Complete execution timeline with machine-readable event codes
 *
 * ## Usage:
 * ```kotlin
 * val config = PatchKitConfig(allowDDL = false, maxBytes = 1_000_000)
 * val patchKit = PatchKit(
 *     registry = mapOf("main" to EngineProvider { createEngine() }),
 *     config = config
 * )
 * 
 * val report = patchKit.apply(patchJsonBytes)
 * if (report.success) {
 *     println("Patch applied successfully: ${report.affectedRows} rows affected")
 * }
 * ```
 *
 * @param registry Map of target aliases to database engine providers for multi-database support
 * @param config Configuration controlling validation, timeouts, and security policies
 */
class PatchKit(
    private val registry: Map<String, EngineProvider>,
    private val config: PatchKitConfig = PatchKitConfig(),
) {
    private val validators: List<PatchValidator> =
        buildList {
            add(SizeValidator(config.maxBytes, config.maxActions))
            add(MultiStatementValidator())
            if (config.verifyHash) add(HashValidator())
            if (!config.allowDDL) add(DmlOnlyValidator())
        }

    private val executor =
        PatchExecutor(
            ExecConfig(
                perActionTimeoutMs = config.perActionTimeoutMs,
                checksInReadTx = config.checksInReadTx,
            ),
        )

    /**
     * Apply a JSON patch to the target database with full validation and safety checks.
     *
     * This method orchestrates the complete patch application lifecycle:
     * 1. **Parse**: Deserialize JSON patch with strict validation
     * 2. **Validate**: Run validation chain (size, hash, DDL restrictions, etc.)
     * 3. **Idempotency Check**: Skip if patch already applied successfully
     * 4. **Preconditions**: Verify database state meets patch requirements
     * 5. **Execute Actions**: Run all actions within single IMMEDIATE transaction
     * 6. **Postconditions**: Validate resulting database state
     * 7. **Record Success**: Mark patch as applied for future idempotency
     *
     * All actions execute within a single ACID transaction. If any step fails, the entire
     * transaction rolls back and no changes are persisted.
     *
     * @param rawPatchBytes The JSON patch as byte array
     * @return ExecutionReport containing detailed timeline, success status, and affected row count
     * @throws IllegalArgumentException if patch targets an unknown database
     */
    suspend fun apply(rawPatchBytes: ByteArray): ExecutionReport {
        val start = now()
        val events = mutableListOf<ExecutionEvent>()

        return try {
            // --- Parse
            val patch = config.json.decodeFromString(Patch.serializer(), rawPatchBytes.decodeToString())

            // --- Validate
            for (v in validators) {
                when (val res = v.validate(patch, rawPatchBytes)) {
                    is ValidationResult.Success -> Unit
                    is ValidationResult.Failure -> {
                        events += event(EventCode.VALIDATION_FAIL, res.message, mapOf("code" to res.code))
                        return ExecutionReport(patch.id, events, start, now())
                    }
                }
            }

            // --- Resolve engine
            val provider =
                registry[patch.target]
                    ?: throw IllegalArgumentException("Unknown target: ${patch.target}")
            val engine = provider.provide()

            // --- Idempotency
            config.idempotency?.initialize(engine)
            if (config.idempotency?.hasBeenApplied(patch.id, engine) == true) {
                events += event(EventCode.IDEMPOTENT_SKIP, "Patch ${patch.id} already applied")
                return ExecutionReport(patch.id, events, start, now())
            }

            // --- Execute
            val report = executor.execute(patch, engine, config.totalTimeoutMs)

            // --- Record success
            if (report.success) {
                config.idempotency?.recordApplication(patch.id, engine, patch.metadata.toString())
            }
            // We return the executor's report directly (it already contains the timeline)
            report
        } catch (t: Throwable) {
            events +=
                event(
                    EventCode.PATCH_FAILURE,
                    t.message ?: "Failed to apply patch",
                    mapOf("exception" to (t::class.simpleName ?: "Exception")),
                )
            ExecutionReport("unknown", events, start, now())
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun event(
        code: EventCode,
        msg: String,
        detail: Map<String, String> = emptyMap(),
    ) = ExecutionEvent(ts = now(), code = code, message = msg, detail = detail)
}
