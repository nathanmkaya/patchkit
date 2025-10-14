package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DdlAndConfigAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun ddl_blocked_without_flag() =
        runBlocking {
            val patch =
                patchJson(
                    id = "create-labels",
                    preconditions = listOf(conditionTableAbsent("labels")),
                    actions = listOf(sql("CREATE TABLE labels(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")),
                    postconditions = listOf(conditionTableExists("labels")),
                )

            val report = applyPatch(patch)
            assertFalse(report.success)
            assertFalse(tableExists("labels"))
        }

    @Test
    fun ddl_allowed_with_flag_creates_table() =
        runBlocking {
            val patch =
                patchJson(
                    id = "create-labels-table",
                    preconditions = listOf(conditionTableAbsent("labels")),
                    actions = listOf(sql("CREATE TABLE labels(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")),
                    postconditions = listOf(conditionTableExists("labels")),
                )

            val report = applyPatch(patch, config = PatchKitConfig(allowDDL = true))
            assertTrue(report.success)
            assertTrue(tableExists("labels"))
        }

    @Test
    fun ddl_with_postcondition_failure_leaves_table_created() =
        runBlocking {
            val patch =
                patchJson(
                    id = "ddl-post-fail",
                    preconditions = listOf(conditionTableAbsent("labels")),
                    actions = listOf(sql("CREATE TABLE labels(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")),
                    postconditions = listOf(conditionTableExists("labels"), conditionCount("labels", expected = 1, label = "Expect row")),
                )

            val report = applyPatch(patch, config = PatchKitConfig(allowDDL = true))
            assertFalse(report.success)
            assertTrue(tableExists("labels"))
        }

    @Test
    fun checks_in_read_transactions_option_runs_successfully() =
        runBlocking {
            val patch =
                patchJson(
                    id = "read-tx-checks",
                    preconditions = listOf(conditionCount("accounts", expected = 0, label = "empty")),
                    actions = listOf(insertAccount("read-tx", 300)),
                    postconditions = listOf(conditionCount("accounts", expected = 1, label = "one")),
                )

            val report = applyPatch(patch, config = PatchKitConfig(checksInReadTx = true))
            assertTrue(report.success)
        }
}
