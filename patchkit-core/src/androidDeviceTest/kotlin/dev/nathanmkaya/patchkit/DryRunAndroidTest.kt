package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.exec.EventCode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DryRunAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun dry_run_rolls_back_changes() =
        runBlocking {
            val patch =
                patchJson(
                    id = "dry-seed",
                    preconditions = listOf(conditionCount("accounts", expected = 0, label = "empty")),
                    actions = listOf(insertAccount("dry", 123)),
                    postconditions = listOf(conditionCount("accounts", expected = 1, label = "one")),
                )

            val report = applyPatch(patch, config = PatchKitConfig(), dryRun = true)
            assertFalse(report.success)
            assertTrue(report.events.any { it.code == EventCode.DRYRUN_ROLLBACK })
            assertTrue(report.events.none { it.code == EventCode.PATCH_SUCCESS })
            assertEquals(0, accountCount())
        }
}
