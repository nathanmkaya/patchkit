package dev.nathanmkaya.patchkit

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.nathanmkaya.patchkit.driver.EngineProvider
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString

class PatchKitDryRunTest {
    @Test
    fun dry_run_executes_without_committing_changes() = runTest {
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY, balance INTEGER)")

        val patchKit = PatchKit(mapOf("main" to EngineProvider(connection)))
        val patch =
            Patch(
                id = "dry-run",
                target = "main",
                actions =
                    listOf(
                        ParameterizedSqlAction(
                            sql = "INSERT INTO accounts(id, balance) VALUES(?, ?)",
                            parameters = listOf(SqlArg.Int64(1), SqlArg.Int64(500)),
                        )
                    ),
            )

        val json = PatchKitJson.strict.encodeToString(Patch.serializer(), patch)
        val report = patchKit.apply(json, dryRun = true)
        assertFalse(report.success)
        assertTrue(report.events.any { it.code == EventCode.DRYRUN_ROLLBACK })
        assertTrue(report.events.none { it.code == EventCode.PATCH_SUCCESS })

        connection.prepare("SELECT COUNT(*) FROM accounts").use { stmt ->
            assertTrue(stmt.step())
            assertEquals(0, stmt.getInt(0))
        }

        connection
            .prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = '_patchkit_applied'")
            .use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0, stmt.getInt(0))
            }
    }
}
