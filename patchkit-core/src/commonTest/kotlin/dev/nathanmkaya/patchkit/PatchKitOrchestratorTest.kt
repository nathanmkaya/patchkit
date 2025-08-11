package dev.nathanmkaya.patchkit

import dev.nathanmkaya.patchkit.engine.EngineProvider
import dev.nathanmkaya.patchkit.engine.SqlScalar
import dev.nathanmkaya.patchkit.engine.SqliteEngine
import dev.nathanmkaya.patchkit.engine.TransactionalEngine
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.idempotency.IdempotencyManager
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchKitOrchestratorTest {
    // --- Test doubles ---

    private class InMemoryIdempotency(
        preApplied: Set<String> = emptySet(),
    ) : IdempotencyManager {
        val applied = preApplied.toMutableSet()

        override suspend fun initialize(engine: SqliteEngine) { /* no-op */ }

        override suspend fun hasBeenApplied(
            patchId: String,
            engine: SqliteEngine,
        ): Boolean = applied.contains(patchId)

        override suspend fun recordApplication(
            patchId: String,
            engine: SqliteEngine,
            metadata: String?,
        ) {
            applied += patchId
        }
    }

    /** Engine that throws if any DML executes (for validation/skip tests). */
    private class ThrowingEngine : TransactionalEngine {
        override suspend fun queryScalar(
            sql: String,
            args: List<SqlArg>,
        ): SqlScalar? = SqlScalar.Null

        override suspend fun execute(
            sql: String,
            args: List<SqlArg>,
        ): Int = error("Should not execute")

        override suspend fun <T> inTransaction(
            immediate: Boolean,
            block: suspend () -> T,
        ): T = error("Should not tx")
    }

    /** Engine that returns fixed results (no pre/post checks here). */
    private class FixedEngine(
        private val affected: Int,
    ) : TransactionalEngine {
        override suspend fun queryScalar(
            sql: String,
            args: List<SqlArg>,
        ): SqlScalar? = SqlScalar.Null

        override suspend fun execute(
            sql: String,
            args: List<SqlArg>,
        ): Int = affected

        override suspend fun <T> inTransaction(
            immediate: Boolean,
            block: suspend () -> T,
        ): T = block()
    }

    // --- Tests ---

    @Test
    fun validation_failure_short_circuits_and_engine_is_not_used() =
        runTest {
            val json =
                """
                {
                  "version": 1,
                  "id": "bad-ddl",
                  "target": "main",
                  "actions": [
                    { "type": "SqlAction", "sql": "ALTER TABLE payments ADD COLUMN z INTEGER" }
                  ]
                }
                """.trimIndent().encodeToByteArray()

            val orchestrator =
                PatchKit(
                    registry = mapOf("main" to EngineProvider { ThrowingEngine() }),
                    config = PatchKitConfig(allowDDL = false), // default anyway
                )

            val report = orchestrator.apply(json)
            assertFalse(report.success)
            assertEquals("bad-ddl", report.patchId)
            assertTrue(report.events.any { it.code == EventCode.VALIDATION_FAIL })
            // No executor events should be present like TX_BEGIN, etc.
            assertFalse(report.events.any { it.code == EventCode.TX_BEGIN })
        }

    @Test
    fun idempotent_skip_returns_skip_event_and_does_not_execute() =
        runTest {
            val json =
                """
                {
                  "version": 1,
                  "id": "already",
                  "target": "main",
                  "actions": [ { "type": "SqlAction", "sql": "UPDATE t SET x=1" } ]
                }
                """.trimIndent().encodeToByteArray()

            val idm = InMemoryIdempotency(preApplied = setOf("already"))
            val orchestrator =
                PatchKit(
                    registry = mapOf("main" to EngineProvider { ThrowingEngine() }),
                    config = PatchKitConfig(idempotency = idm),
                )

            val report = orchestrator.apply(json)
            assertFalse(report.success) // No PATCH_SUCCESS; we exited early with skip
            assertTrue(report.events.any { it.code == EventCode.IDEMPOTENT_SKIP })
        }

    @Test
    fun success_path_executes_and_records_idempotency() =
        runTest {
            val json =
                """
                {
                  "version": 1,
                  "id": "ok-1",
                  "target": "main",
                  "actions": [ 
                    { "type": "SqlAction", "sql": "UPDATE t SET x=1" } 
                  ],
                  "metadata": { "sha256": "ignore-in-test" }
                }
                """.trimIndent().encodeToByteArray()

            // Skip hash verification for this test to avoid computing it here
            val idm = InMemoryIdempotency()
            val orchestrator =
                PatchKit(
                    registry = mapOf("main" to EngineProvider { FixedEngine(affected = 2) }),
                    config = PatchKitConfig(verifyHash = false, idempotency = idm),
                )

            val report = orchestrator.apply(json)
            assertTrue(report.success)
            assertEquals(2, report.affectedRows)
            assertTrue(idm.applied.contains("ok-1"))
        }
}
