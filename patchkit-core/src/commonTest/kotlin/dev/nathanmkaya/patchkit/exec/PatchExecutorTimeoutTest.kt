package dev.nathanmkaya.patchkit.exec

import dev.nathanmkaya.patchkit.engine.SqlScalar
import dev.nathanmkaya.patchkit.engine.TransactionalEngine
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class PatchExecutorTimeoutTest {
    @Test
    fun per_action_timeout_is_reported() =
        runTest {
            val engine = SlowEngine(delayMs = 50)
            val executor = PatchExecutor(ExecConfig(perActionTimeoutMs = 10))
            val patch =
                Patch(
                    id = "timeout",
                    target = "main",
                    actions = listOf(SqlAction("UPDATE t SET x = 1")),
                )

            val report = executor.execute(patch, engine, totalTimeoutMs = 5_000)
            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.ACTION_FAIL })
            assertTrue(report.events.any { it.code == EventCode.TX_ROLLBACK })
        }

    private class SlowEngine(
        private val delayMs: Long,
    ) : TransactionalEngine {
        override suspend fun queryScalar(
            sql: String,
            args: List<SqlArg>,
        ): SqlScalar = SqlScalar.Int64(0)

        override suspend fun execute(
            sql: String,
            args: List<SqlArg>,
        ): Int {
            delay(delayMs)
            return 0
        }

        override suspend fun <T> inTransaction(
            immediate: Boolean,
            block: suspend () -> T,
        ): T = block()
    }
}
