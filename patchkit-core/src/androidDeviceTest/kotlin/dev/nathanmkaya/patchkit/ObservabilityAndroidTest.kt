package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.report.pretty
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObservabilityAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun event_timeline_matches_expected_order() =
        runBlocking {
            val patch =
                patchJson(
                    id = "timeline",
                    actions = listOf(insertAccount("timeline", 5)),
                    postconditions = listOf(conditionCount("accounts", expected = 1, label = "one")),
                )

            val report = applyPatch(patch)
            assertTrue(report.success)
            val codes = report.events.map { it.code }
            assertTrue(codes.indexOf(EventCode.PRECHECK_START) < codes.indexOf(EventCode.TX_BEGIN))
            assertTrue(codes.contains(EventCode.ACTION_OK))
            assertTrue(codes.last() == EventCode.PATCH_SUCCESS)
        }

    @Test
    fun pretty_formatter_includes_summary_information() =
        runBlocking {
            val patch =
                patchJson(
                    id = "pretty",
                    actions = listOf(insertAccount("pretty", 50)),
                    postconditions = listOf(conditionCount("accounts", expected = 1, label = "one")),
                )

            val report = applyPatch(patch)
            val text = report.pretty()
            assertTrue(text.contains("patchId   : pretty"))
            assertTrue(text.contains("success   : true"))
            assertTrue(text.contains("timeline"))
        }
}
