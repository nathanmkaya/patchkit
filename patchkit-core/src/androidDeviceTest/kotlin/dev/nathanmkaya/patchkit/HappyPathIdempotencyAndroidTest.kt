package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.exec.EventCode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HappyPathIdempotencyAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun apply_patch_creates_account_and_is_idempotent() = runBlocking {
        val seedPatch =
            patchJson(
                id = "seed-account",
                preconditions =
                    listOf(conditionCount("accounts", expected = 0, label = "No accounts")),
                actions = listOf(insertAccount("demo", 5_000)),
                postconditions =
                    listOf(conditionCount("accounts", expected = 1, label = "One account")),
            )

        val first = applyPatch(seedPatch)
        assertTrue(first.success)
        assertEquals(1, accountCount())

        val replay = applyPatch(seedPatch)
        assertTrue(replay.events.any { it.code == EventCode.IDEMPOTENT_SKIP })
        assertEquals(1, accountCount())
    }

    @Test
    fun idempotency_record_persists_metadata_and_prevents_duplicates() = runBlocking {
        val patch =
            patchJson(
                id = "metadata-record",
                actions = listOf(insertAccount("meta", 123)),
                postconditions =
                    listOf(conditionCount("accounts", expected = 1, label = "One account")),
                metadata = mapOf("owner" to "qa"),
            )

        val applied = applyPatch(patch)
        assertTrue(applied.success)
        val record = idempotencyRecord("metadata-record")
        assertNotNull(record)
        assertTrue(record.contains("owner=qa"))

        val replay =
            patchJson(
                id = "metadata-record",
                actions = listOf(insertAccount("meta", 999)),
                postconditions =
                    listOf(conditionCount("accounts", expected = 1, label = "Still one")),
                metadata = mapOf("owner" to "qa2"),
            )
        val skipped = applyPatch(replay)
        assertTrue(skipped.events.any { it.code == EventCode.IDEMPOTENT_SKIP })
        assertEquals(1, accountCount())
    }

    @Test
    fun preseeded_idempotency_row_skips_execution() = runBlocking {
        insertIdempotencyRecord("skip-me")

        val patch =
            patchJson(
                id = "skip-me",
                actions = listOf(insertAccount("should-not-run", 50)),
                postconditions =
                    listOf(conditionCount("accounts", expected = 1, label = "Should remain empty")),
            )

        val report = applyPatch(patch)
        assertTrue(report.events.any { it.code == EventCode.IDEMPOTENT_SKIP })
        assertEquals(0, accountCount())
    }
}
