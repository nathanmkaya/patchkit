package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.exec.EventCode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IoHelpersAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun apply_directory_executes_patches_in_filename_order() = runBlocking {
        val dir = createTempDir("patchkit-batch")
        writePatchFile(
            dir.resolve("001_seed.json"),
            patchJson(id = "001-batch", actions = listOf(insertAccount("batch-1", 1))),
        )
        writePatchFile(
            dir.resolve("002_seed.json"),
            patchJson(id = "002-batch", actions = listOf(insertAccount("batch-2", 2))),
        )

        val reports = applyDirectory(dir.absolutePath.toPath())
        assertEquals(2, reports.size)
        assertTrue(reports.all { it.success })
        assertEquals(setOf("batch-1", "batch-2"), accountNames().toSet())
    }

    @Test
    fun apply_path_returns_validation_failure_for_empty_file() = runBlocking {
        val file = createTempDir("empty-root").resolve("empty.json").apply { writeText("   ") }
        val report = applyPath(file.absolutePath.toPath())
        assertFalse(report.success)
        val validation = report.events.firstOrNull { it.code == EventCode.VALIDATION_FAIL }
        assertEquals("EMPTY_INPUT", validation?.detail?.get("code"))
    }
}
