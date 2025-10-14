package dev.nathanmkaya.patchkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionAndForeignKeyAndroidTest : BaseAndroidPatchKitTest() {
    @Test
    fun action_failure_rolls_back_transaction() = runBlocking {
        val patch =
            patchJson(
                id = "unique-violation",
                actions =
                    listOf(
                        sql("INSERT INTO accounts(name, balance) VALUES('unique', 1)"),
                        ParameterizedSqlAction(
                            sql = "INSERT INTO accounts(name, balance) VALUES (?, ?)",
                            parameters = listOf(SqlArg.Text("unique"), SqlArg.Int64(2)),
                            description = "Duplicate insert to trigger failure",
                        ),
                    ),
            )

        val report = applyPatch(patch)
        assertFalse(report.success)
        assertTrue(report.events.any { it.code == EventCode.TX_ROLLBACK })
        assertEquals(0, accountCount())
    }

    @Test
    fun foreign_key_violation_rolls_back_transaction() = runBlocking {
        val patch =
            patchJson(
                id = "fk-violation",
                actions =
                    listOf(
                        ParameterizedSqlAction(
                            sql = "INSERT INTO orders(customer_id, amount) VALUES (?, ?)",
                            parameters = listOf(SqlArg.Int64(999), SqlArg.Int64(1_000)),
                            description = "Insert order for missing customer",
                        )
                    ),
            )

        val report = applyPatch(patch)
        assertFalse(report.success)
        assertTrue(report.events.any { it.code == EventCode.TX_ROLLBACK })
        assertEquals(0, tableRowCount("orders"))
    }
}
