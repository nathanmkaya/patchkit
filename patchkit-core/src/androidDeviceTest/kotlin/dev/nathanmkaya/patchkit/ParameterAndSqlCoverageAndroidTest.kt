package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParameterAndSqlCoverageAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun parameter_round_trip_for_all_types() =
        runBlocking {
            val payload = byteArrayOf(1, 2, 3, 4)
            val patch =
                patchJson(
                    id = "parameter-types",
                    actions = listOf(
                        ParameterizedSqlAction(
                            sql = "INSERT INTO blobs(text_col, int_col, real_col, blob_col) VALUES (?, ?, ?, ?)",
                            parameters = listOf(
                                SqlArg.Text("hello"),
                                SqlArg.Int64(42),
                                SqlArg.Real(3.14),
                                SqlArg.Blob(payload),
                            ),
                        ),
                    ),
                    postconditions = listOf(conditionCount("blobs", expected = 1, label = "One blob row")),
                )

            val report = applyPatch(patch)
            assertTrue(report.success)
            val row = querySingle("SELECT text_col, int_col, real_col, blob_col FROM blobs")
            assertContentEquals(payload, row.blob!!)
            assertEquals("hello", row.text)
            assertEquals(42L, row.int)
            assertEquals(3.14, row.real!!, 0.001)
        }

    @Test
    fun with_clause_insert_is_allowed() =
        runBlocking {
            val patch =
                patchJson(
                    id = "with-insert",
                    actions = listOf(
                        sql(
                            """
                            WITH seeded AS (SELECT 'cte-user' AS name, 700 AS balance)
                            INSERT INTO accounts(name, balance)
                            SELECT name, balance FROM seeded
                            """.trimIndent(),
                        ),
                    ),
                    postconditions = listOf(conditionCount("accounts", expected = 1, label = "One CTE row")),
                )

            val report = applyPatch(patch)
            assertTrue(report.success)
            assertEquals(listOf("cte-user"), accountNames())
        }
}
