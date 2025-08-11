package dev.nathanmkaya.patchkit.driver

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.nathanmkaya.patchkit.PatchKit
import dev.nathanmkaya.patchkit.PatchKitConfig
import dev.nathanmkaya.patchkit.exec.EventCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdapterIntegrationTest {
    @Test
    fun end_to_end_patch_applies_and_is_idempotent() =
        runTest {
            // 1) Open a in memory DB using the bundled driver
            val connection = BundledSQLiteDriver().open(":memory:")

            // 2) Seed schema + data (outside PatchKit; DDL is off by default)
            connection.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, active INTEGER DEFAULT 0)")
            connection.execSQL("INSERT INTO users(id, name, active) VALUES (1, 'Alice', 0)")
            connection.execSQL("INSERT INTO users(id, name, active) VALUES (2, 'Bob', 0)")

            // 3) Wire the engine provider
            val provider = EngineProvider(connection)
            val patchKit =
                PatchKit(
                    registry = mapOf("main" to provider),
                    config =
                        PatchKitConfig(
                            verifyHash = false, // keep test simple
                            allowDDL = false, // default
                        ),
                )

            // 4) DML-only patch JSON (parameters use SqlArg-discriminated JSON)
            val patchJson =
                """
                {
                  "version": 1,
                  "id": "activate-users-1",
                  "target": "main",
                  "preconditions": [
                    { "sql": "SELECT COUNT(*) FROM users", "expected": 2 }
                  ],
                  "actions": [
                    {
                      "type": "ParameterizedSqlAction",
                      "sql": "UPDATE users SET active = ? WHERE id = ?",
                      "parameters": [
                        { "type": "Int64", "v": 1 },
                        { "type": "Int64", "v": 1 }
                      ],
                      "description": "Activate Alice"
                    },
                    {
                      "type": "SqlAction",
                      "sql": "UPDATE users SET name = 'Bobby' WHERE id = 2",
                      "description": "Rename Bob"
                    }
                  ],
                  "postconditions": [
                    { "sql": "SELECT COUNT(*) FROM users WHERE active = 1", "expected": 1 },
                    { "sql": "SELECT COUNT(*) FROM users WHERE name = 'Bobby'", "expected": 1 }
                  ]
                }
                """.trimIndent().encodeToByteArray()

            // 5) First run: should succeed and affect 2 rows
            val report1 = patchKit.apply(patchJson)
            assertTrue(report1.success)
            assertEquals(2, report1.affectedRows)
            assertTrue(report1.events.any { it.code == EventCode.TX_BEGIN })
            assertTrue(report1.events.any { it.code == EventCode.ACTION_OK })

            // Verify state via the raw connection
            connection.prepare("SELECT active FROM users WHERE id = 1").use { s ->
                assertTrue(s.step())
                assertEquals(1, s.getInt(0))
            }
            connection.prepare("SELECT name FROM users WHERE id = 2").use { s ->
                assertTrue(s.step())
                assertEquals("Bobby", s.getText(0))
            }

            // 6) Second run: idempotent skip
            val report2 = patchKit.apply(patchJson)
            assertFalse(report2.success) // skip => no PATCH_SUCCESS
            assertTrue(report2.events.any { it.code == EventCode.IDEMPOTENT_SKIP })
        }
}
