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

    /** Apply a raw JSON patch. Returns a full execution report (or validation/skip report). */
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
