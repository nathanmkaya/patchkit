package dev.nathanmkaya.patchkit

import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.model.Condition
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ValidationAndSecurityAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun precondition_failure_does_not_mutate_state() =
        runBlocking {
            connection.execSQL("INSERT INTO accounts(name, balance) VALUES('existing', 2_000)")

            val patch =
                patchJson(
                    id = "expect-empty",
                    preconditions = listOf(conditionCount("accounts", expected = 0, label = "Table empty")),
                    actions = listOf(sql("DELETE FROM accounts")),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.PRECHECK_FAIL })
            assertEquals(1, accountCount())
        }

    @Test
    fun postcondition_failure_reports_and_keeps_committed_state() =
        runBlocking {
            val patch =
                patchJson(
                    id = "postcheck-fails",
                    preconditions = listOf(conditionCount("accounts", expected = 0, label = "No accounts")),
                    actions = listOf(insertAccount("bob", 10_000)),
                    postconditions = listOf(conditionCount("accounts", expected = 2, label = "Expect two")),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.POSTCHECK_FAIL })
            assertEquals(1, accountCount())
        }

    @Test
    fun multi_statement_patch_is_rejected() =
        runBlocking {
            val patch =
                patchJson(
                    id = "multi-statement",
                    preconditions = listOf(conditionCount("accounts", expected = 0, label = "Empty")),
                    actions = listOf(sql("UPDATE accounts SET balance = 1; DELETE FROM accounts")),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            val validation = report.events.firstOrNull { it.code == EventCode.VALIDATION_FAIL }
            assertEquals("MULTI_STATEMENT", validation?.detail?.get("code"))
        }

    @Test
    fun hash_mismatch_fails_validation() =
        runBlocking {
            val patch =
                patchJson(
                    id = "hash-mismatch",
                    preconditions = listOf(conditionCount("accounts", expected = 0, label = "Empty")),
                    actions = listOf(sql("DELETE FROM accounts")),
                    metadata = mapOf("sha256" to "deadbeef"),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            val validation = report.events.firstOrNull { it.code == EventCode.VALIDATION_FAIL }
            assertEquals("HASH_MISMATCH", validation?.detail?.get("code"))
        }

    @Test
    fun precondition_non_numeric_fails_with_descriptive_message() =
        runBlocking {
            val patch =
                patchJson(
                    id = "non-numeric",
                    preconditions = listOf(
                        Condition(
                            sql = "SELECT 'text'",
                            expected = 1,
                            description = "Should be numeric",
                        ),
                    ),
                    actions = listOf(insertAccount("numeric", 1)),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.PRECHECK_FAIL })
            assertEquals(0, accountCount())
        }

    @Test
    fun unknown_target_alias_emits_patch_failure() =
        runBlocking {
            val patch =
                patchJson(
                    id = "unknown-target",
                    target = "secondary",
                    actions = listOf(insertAccount("secondary", 1)),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.PATCH_FAILURE })
            assertEquals(0, accountCount())
        }
}
