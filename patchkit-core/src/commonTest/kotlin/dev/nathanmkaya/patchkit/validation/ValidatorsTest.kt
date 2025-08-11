package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.crypto.sha256Hex
import dev.nathanmkaya.patchkit.model.Action
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ValidatorsTest {
    // ---------- helpers ----------
    private fun patchWithActions(vararg actions: Action): Patch =
        Patch(
            id = "p-1",
            target = "main",
            actions = actions.toList(),
        )

    // ---------- SizeValidator ----------
    @Test
    fun size_validator_rejects_when_raw_bytes_exceed_limit() =
        runTest {
            val v = SizeValidator(maxBytes = 4, maxActions = 10)
            val patch = patchWithActions(SqlAction("UPDATE t SET x=1"))
            val res = v.validate(patch, rawBytes = ByteArray(5)) // 5 > 4
            assertTrue(res is ValidationResult.Failure)
            assertEquals("SIZE_EXCEEDED", (res as ValidationResult.Failure).code)
        }

    @Test
    fun size_validator_rejects_when_action_count_exceeds_limit() =
        runTest {
            val v = SizeValidator(maxBytes = 512_000, maxActions = 2)
            val patch =
                patchWithActions(
                    SqlAction("UPDATE a SET x=1"),
                    SqlAction("UPDATE b SET y=2"),
                    SqlAction("UPDATE c SET z=3"),
                )
            val res = v.validate(patch, rawBytes = null)
            assertTrue(res is ValidationResult.Failure)
            assertEquals("TOO_MANY_ACTIONS", (res as ValidationResult.Failure).code)
        }

    // ---------- DmlOnlyValidator ----------
    @Test
    fun dml_only_allows_dml_and_blocks_ddl() =
        runTest {
            val ok =
                patchWithActions(
                    SqlAction("UPDATE t SET x=1"),
                    ParameterizedSqlAction("INSERT INTO t(a) VALUES(?)", listOf(SqlArg.Int64(1))),
                )
            val ddlCreate = patchWithActions(SqlAction("CREATE TABLE t(x INTEGER)"))
            val ddlAlter = patchWithActions(SqlAction("ALTER TABLE t ADD COLUMN y INTEGER"))

            val dmlOnly = DmlOnlyValidator()
            assertTrue(dmlOnly.validate(ok, null) is ValidationResult.Success)

            val r1 = dmlOnly.validate(ddlCreate, null)
            assertTrue(r1 is ValidationResult.Failure)
            assertEquals("DDL_NOT_ALLOWED", (r1 as ValidationResult.Failure).code)

            val r2 = dmlOnly.validate(ddlAlter, null)
            assertTrue(r2 is ValidationResult.Failure)
        }

    // ---------- MultiStatementValidator ----------
    @Test
    fun multi_statement_rejects_multiple_statements_and_allows_single_trailing_semicolon() =
        runTest {
            val goodNoSemi = patchWithActions(SqlAction("UPDATE t SET x=1"))
            val goodTrailing = patchWithActions(SqlAction("UPDATE t SET x=1;"))
            val goodInQuotes = patchWithActions(SqlAction("UPDATE t SET note='a; b';"))
            val badMultiple = patchWithActions(SqlAction("UPDATE t SET x=1; DELETE FROM t;"))
            val badMultipleNoTrailing = patchWithActions(SqlAction("UPDATE t SET x=1; DELETE FROM t"))

            val v = MultiStatementValidator()
            assertTrue(v.validate(goodNoSemi, null) is ValidationResult.Success)
            assertTrue(v.validate(goodTrailing, null) is ValidationResult.Success)
            assertTrue(v.validate(goodInQuotes, null) is ValidationResult.Success)

            val r1 = v.validate(badMultiple, null)
            assertTrue(r1 is ValidationResult.Failure)
            assertEquals("MULTI_STATEMENT", (r1 as ValidationResult.Failure).code)

            val r2 = v.validate(badMultipleNoTrailing, null)
            assertTrue(r2 is ValidationResult.Failure)
        }

    // ---------- HashValidator ----------
    @Test
    fun hash_validator_passes_on_match_and_fails_on_mismatch_or_missing_bytes() =
        runTest {
            val jsonBytes = """{"k":"v"}""".encodeToByteArray()
            val expected = sha256Hex(jsonBytes)

            val pWithHash =
                patchWithActions(SqlAction("UPDATE t SET x=1")).copy(
                    metadata = mapOf("sha256" to expected),
                )
            val pWithWrongHash =
                patchWithActions(SqlAction("UPDATE t SET x=1")).copy(
                    metadata = mapOf("sha256" to "deadbeef"),
                )
            val pWithHashButNoBytes =
                patchWithActions(SqlAction("UPDATE t SET x=1")).copy(
                    metadata = mapOf("sha256" to expected),
                )

            val hv = HashValidator()

            // match
            assertTrue(hv.validate(pWithHash, jsonBytes) is ValidationResult.Success)

            // mismatch
            val mismatch = hv.validate(pWithWrongHash, jsonBytes)
            assertTrue(mismatch is ValidationResult.Failure)
            assertEquals("HASH_MISMATCH", (mismatch as ValidationResult.Failure).code)

            // expected but missing raw bytes
            val missing = hv.validate(pWithHashButNoBytes, null)
            assertTrue(missing is ValidationResult.Failure)
            assertEquals("HASH_MISSING_BYTES", (missing as ValidationResult.Failure).code)
        }
}
