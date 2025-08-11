package dev.nathanmkaya.patchkit.exec

import dev.nathanmkaya.patchkit.engine.PostconditionFailedException
import dev.nathanmkaya.patchkit.engine.PreconditionFailedException
import dev.nathanmkaya.patchkit.engine.TransactionalEngine
import dev.nathanmkaya.patchkit.engine.asLongOrZero
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Tunables for executor behavior. */
data class ExecConfig(
    val perActionTimeoutMs: Long = 10_000,
    val checksInReadTx: Boolean = false,
)

/**
 * Executes a Patch against a TransactionalEngine:
 * - Preconditions outside mutating tx (optional short read tx)
 * - Single transaction for actions (BEGIN IMMEDIATE)
 * - Postconditions outside mutating tx (optional short read tx)
 * - Per-action and total timeouts
 */
class PatchExecutor(
    private val config: ExecConfig = ExecConfig(),
) {
    suspend fun execute(
        patch: Patch,
        engine: TransactionalEngine,
        totalTimeoutMs: Long,
    ): ExecutionReport =
        withTimeout(totalTimeoutMs) {
            val events = mutableListOf<ExecutionEvent>()
            val startTime = now()
            var totalRows = 0

            try {
                // -------- Preconditions
                events += event(EventCode.PRECHECK_START, "Checking ${patch.preconditions.size} preconditions")
                val runPre: suspend () -> Unit = {
                    for (c in patch.preconditions) {
                        val actual = engine.queryScalar(c.sql).asLongOrZero()
                        if (!c.operator.evaluate(actual, c.expected)) {
                            events +=
                                event(
                                    EventCode.PRECHECK_FAIL,
                                    c.description ?: c.sql,
                                    mapOf(
                                        "actual" to actual.toString(),
                                        "expected" to c.expected.toString(),
                                        "operator" to c.operator.name,
                                    ),
                                )
                            throw PreconditionFailedException("Precondition failed: expected ${c.operator} ${c.expected}, got $actual")
                        }
                        events += event(EventCode.PRECHECK_OK, c.description ?: c.sql)
                    }
                }
                if (config.checksInReadTx) engine.inTransaction(immediate = false) { runPre() } else runPre()

                // -------- Actions (single tx)
                engine.inTransaction(immediate = true) {
                    events += event(EventCode.TX_BEGIN, "Transaction started (IMMEDIATE)")

                    for (a in patch.actions) {
                        val label =
                            a.description ?: when (a) {
                                is SqlAction -> a.sql.take(50)
                                is ParameterizedSqlAction -> a.sql.take(50)
                            }
                        events += event(EventCode.ACTION_START, label)

                        try {
                            val rows =
                                withTimeout(config.perActionTimeoutMs) {
                                    when (a) {
                                        is SqlAction -> engine.execute(a.sql)
                                        is ParameterizedSqlAction -> engine.execute(a.sql, a.parameters)
                                    }
                                }
                            totalRows += rows
                            events +=
                                event(
                                    EventCode.ACTION_OK,
                                    label,
                                    mapOf("rows" to rows.toString()),
                                )
                        } catch (t: Throwable) {
                            events +=
                                event(
                                    EventCode.ACTION_FAIL,
                                    label,
                                    mapOf("exception" to (t::class.simpleName ?: "Exception")),
                                )
                            throw t
                        }
                    }

                    events += event(EventCode.TX_COMMIT, "Transaction committed")
                }

                // -------- Postconditions
                events += event(EventCode.POSTCHECK_START, "Checking ${patch.postconditions.size} postconditions")
                val runPost: suspend () -> Unit = {
                    for (c in patch.postconditions) {
                        val actual = engine.queryScalar(c.sql).asLongOrZero()
                        if (!c.operator.evaluate(actual, c.expected)) {
                            events +=
                                event(
                                    EventCode.POSTCHECK_FAIL,
                                    c.description ?: c.sql,
                                    mapOf(
                                        "actual" to actual.toString(),
                                        "expected" to c.expected.toString(),
                                        "operator" to c.operator.name,
                                    ),
                                )
                            throw PostconditionFailedException("Postcondition failed: expected ${c.operator} ${c.expected}, got $actual")
                        }
                        events += event(EventCode.POSTCHECK_OK, c.description ?: c.sql)
                    }
                }
                if (config.checksInReadTx) engine.inTransaction(immediate = false) { runPost() } else runPost()

                // -------- Success
                events += event(EventCode.PATCH_SUCCESS, "Patch ${patch.id} applied successfully")
                ExecutionReport(patch.id, events, startTime, now(), totalRows)
            } catch (t: Throwable) {
                // Note: TX_ROLLBACK is emitted by engine implementation if desired;
                // we record a terminal failure here.
                val code =
                    when (t) {
                        is PreconditionFailedException -> EventCode.PRECHECK_FAIL
                        is PostconditionFailedException -> EventCode.POSTCHECK_FAIL
                        else -> EventCode.PATCH_FAILURE
                    }
                events += event(code, t.message ?: "Patch failed", mapOf("exception" to (t::class.simpleName ?: "Exception")))
                ExecutionReport(patch.id, events, startTime, now(), affectedRows = 0)
            }
        }

    private fun event(
        code: EventCode,
        msg: String,
        detail: Map<String, String> = emptyMap(),
    ) = ExecutionEvent(ts = now(), code = code, message = msg, detail = detail)

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
