package dev.nathanmkaya.patchkit.exec

import dev.nathanmkaya.patchkit.engine.SqlScalar
import dev.nathanmkaya.patchkit.engine.TransactionalEngine
import dev.nathanmkaya.patchkit.model.Condition
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Happy path using a programmable fake engine. */
class PatchExecutorTest {
    @Test
    fun happy_path_produces_success_events_and_row_count() =
        runTest {
            val patch =
                Patch(
                    id = "ok-1",
                    target = "main",
                    preconditions =
                        listOf(
                            Condition(sql = "SELECT 1", expected = 1),
                        ),
                    actions =
                        listOf(
                            SqlAction("UPDATE t SET x=1"),
                            ParameterizedSqlAction(
                                "UPDATE t SET y=? WHERE id=?",
                                parameters = listOf(SqlArg.Int64(2), SqlArg.Int64(7)),
                            ),
                        ),
                    postconditions =
                        listOf(
                            Condition(sql = "SELECT 0", expected = 0),
                        ),
                )

            val eng =
                QueueEngine(
                    scalar = mutableListOf(SqlScalar.Int64(1), SqlScalar.Int64(0)), // pre=1, post=0
                    exec = mutableListOf(2, 3), // two actions
                )

            val executor = PatchExecutor(ExecConfig(perActionTimeoutMs = 5_000, checksInReadTx = false))
            val report = executor.execute(patch, eng, totalTimeoutMs = 10_000)

            assertTrue(report.success, "Report should be successful")
            assertEquals(5, report.affectedRows)
            // Contains key milestones
            assertTrue(report.events.any { it.code == EventCode.TX_BEGIN })
            assertTrue(report.events.any { it.code == EventCode.ACTION_OK && it.detail["rows"] == "2" })
            assertTrue(report.events.any { it.code == EventCode.POSTCHECK_OK })
            assertEquals("ok-1", report.patchId)
        }

    @Test
    fun precondition_failure_yields_failure_event_and_no_success() =
        runTest {
            val patch =
                Patch(
                    id = "pre-fail",
                    target = "main",
                    preconditions = listOf(Condition(sql = "SELECT 0", expected = 1)),
                    actions = listOf(SqlAction("UPDATE t SET x=1")),
                )

            val eng =
                QueueEngine(
                    scalar = mutableListOf(SqlScalar.Int64(0)), // pre returns 0 -> fail
                )

            val executor = PatchExecutor()
            val report = executor.execute(patch, eng, totalTimeoutMs = 5_000)

            assertFalse(report.success)
            // Ensure we recorded PRECHECK_FAIL as terminal
            assertTrue(report.events.any { it.code == EventCode.PRECHECK_FAIL })
            // No TX_BEGIN should have happened
            assertFalse(report.events.any { it.code == EventCode.TX_BEGIN })
            // Last event should be failure-type
            assertTrue(report.events.last().code in setOf(EventCode.PRECHECK_FAIL, EventCode.PATCH_FAILURE))
        }

    @Test
    fun postcondition_failure_marks_failure_after_actions_and_commit() =
        runTest {
            val patch =
                Patch(
                    id = "post-fail",
                    target = "main",
                    preconditions = listOf(Condition("SELECT 1", expected = 1)),
                    actions = listOf(SqlAction("UPDATE t SET x=1")),
                    postconditions = listOf(Condition("SELECT 1", expected = 0)),
                )

            val eng =
                QueueEngine(
                    scalar =
                        mutableListOf(
                            SqlScalar.Int64(1), // pre ok
                            SqlScalar.Int64(1), // post -> fails expected 0
                        ),
                    exec = mutableListOf(1),
                )

            val executor = PatchExecutor()
            val report = executor.execute(patch, eng, totalTimeoutMs = 5_000)

            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.TX_BEGIN })
            assertTrue(report.events.any { it.code == EventCode.ACTION_OK })
            assertTrue(report.events.any { it.code == EventCode.POSTCHECK_FAIL })
        }

    @Test
    fun action_timeout_causes_action_fail_and_overall_failure() =
        runTest {
            val patch =
                Patch(
                    id = "timeout-1",
                    target = "main",
                    preconditions = listOf(Condition("SELECT 1", expected = 1)),
                    actions = listOf(SqlAction("UPDATE slow SET x=1")),
                )

            val eng =
                SlowEngineForTimeouts(
                    preScalars = listOf(SqlScalar.Int64(1)),
                    postScalars = emptyList(),
                    actionDelayMs = 50L,
                )
            val executor = PatchExecutor(ExecConfig(perActionTimeoutMs = 10L, checksInReadTx = false))

            val report = executor.execute(patch, eng, totalTimeoutMs = 5_000)

            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.ACTION_FAIL })
            assertTrue(report.events.any { it.code == EventCode.PATCH_FAILURE })
        }
}

/** A minimal programmable engine for these tests. */
private class QueueEngine(
    scalar: MutableList<SqlScalar?> = mutableListOf(),
    exec: MutableList<Int> = mutableListOf(),
) : TransactionalEngine {
    private val scalarQ = ArrayDeque(scalar)
    private val execQ = ArrayDeque(exec)

    override suspend fun queryScalar(
        sql: String,
        args: List<SqlArg>,
    ): SqlScalar? = if (scalarQ.isEmpty()) SqlScalar.Null else scalarQ.removeFirst()

    override suspend fun execute(
        sql: String,
        args: List<SqlArg>,
    ): Int = if (execQ.isEmpty()) 0 else execQ.removeFirst()

    override suspend fun <T> inTransaction(
        immediate: Boolean,
        block: suspend () -> T,
    ): T {
        // We don't emit TX_ events here; executor records them.
        return try {
            block()
        } catch (t: Throwable) {
            throw t
        }
    }
}

/** Engine that delays during execute to trigger per-action timeouts. */
private class SlowEngineForTimeouts(
    private val preScalars: List<SqlScalar?>,
    private val postScalars: List<SqlScalar?>,
    private val actionDelayMs: Long,
) : TransactionalEngine {
    private var scalarIndex = 0

    override suspend fun queryScalar(
        sql: String,
        args: List<SqlArg>,
    ): SqlScalar? {
        val list = preScalars + postScalars
        val v = if (scalarIndex < list.size) list[scalarIndex] else SqlScalar.Null
        scalarIndex++
        return v
    }

    override suspend fun execute(
        sql: String,
        args: List<SqlArg>,
    ): Int {
        delay(actionDelayMs)
        return 1
    }

    override suspend fun <T> inTransaction(
        immediate: Boolean,
        block: suspend () -> T,
    ): T = block()
}
