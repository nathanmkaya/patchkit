package dev.nathanmkaya.patchkit.engine

import dev.nathanmkaya.patchkit.model.SqlArg
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineSpiTest {
    @Test
    fun transaction_commits_on_success_and_rolls_back_on_failure() =
        runTest {
            val eng = FakeEngine()

            val result =
                eng.inTransaction(immediate = true) {
                    // simulate work
                    42
                }
            assertEquals(42, result)
            assertEquals(1, eng.beganImmediate)
            assertEquals(0, eng.beganDeferred)
            assertEquals(1, eng.committed)
            assertEquals(0, eng.rolledBack)

            // Now force a failure
            assertFailsWith<IllegalStateException> {
                eng.inTransaction(immediate = false) {
                    error("boom")
                }
            }
            assertEquals(1, eng.beganDeferred)
            assertEquals(1, eng.rolledBack)
        }

    @Test
    fun queryScalar_and_execute_record_calls_and_return_programmed_values() =
        runTest {
            val eng = FakeEngine()
            eng.queueScalar(SqlScalar.Int64(5))
            eng.queueScalar(SqlScalar.Text("10"))
            eng.queueExecute(3)
            eng.queueExecute(0)

            val s1 = eng.queryScalar("SELECT COUNT(*) FROM t")
            val s2 = eng.queryScalar("SELECT name FROM users WHERE id = ?", listOf(SqlArg.Int64(7)))
            val e1 = eng.execute("UPDATE t SET x = ?", listOf(SqlArg.Text("v")))
            val e2 = eng.execute("DELETE FROM t WHERE id = 1")

            assertEquals(5L, s1.asLongOrZero())
            assertEquals(10L, s2.asLongOrZero()) // Text coercion
            assertEquals(3, e1)
            assertEquals(0, e2)

            assertEquals(2, eng.queries.size)
            assertEquals(2, eng.executions.size)
            assertEquals(listOf(SqlArg.Int64(7)), eng.queries[1].second)
            assertEquals(listOf(SqlArg.Text("v")), eng.executions[0].second)
        }
}
