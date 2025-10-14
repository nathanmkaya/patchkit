package dev.nathanmkaya.patchkit.validation

import dev.nathanmkaya.patchkit.crypto.sha256Hex
import dev.nathanmkaya.patchkit.model.Action
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ValidatorsTest {
    // ---------- helpers ----------
    private fun patchWithActions(vararg actions: Action): Patch =
        Patch(id = "p-1", target = "main", actions = actions.toList())

    // ---------- SizeValidator ----------
    @Test
    fun size_validator_rejects_when_raw_bytes_exceed_limit() = runTest {
        val v = SizeValidator(maxBytes = 4, maxActions = 10)
        val patch = patchWithActions(SqlAction("UPDATE t SET x=1"))
        val res = v.validate(patch, rawBytes = ByteArray(5)) // 5 > 4
        assertTrue(res is ValidationResult.Failure)
        assertEquals("SIZE_EXCEEDED", (res as ValidationResult.Failure).code)
    }

    @Test
    fun size_validator_rejects_when_action_count_exceeds_limit() = runTest {
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
    fun dml_only_allows_configured_verbs_and_pragmas() = runTest {
        val ok =
            patchWithActions(
                SqlAction("-- comment\nUPDATE t SET x=1"),
                ParameterizedSqlAction("  insert into t(a) values(?)  ", listOf(SqlArg.Int64(1))),
                SqlAction("WITH cte AS (SELECT 1) UPDATE t SET x=2"),
                SqlAction("PRAGMA foreign_keys = ON"),
            )
        val badVerb = patchWithActions(SqlAction("TRUNCATE TABLE t"))
        val badPragma = patchWithActions(SqlAction("PRAGMA writable_schema = ON"))

        val validator = DmlOnlyValidator()
        val success = validator.validate(ok, null)
        assertTrue(success is ValidationResult.Success)

        val badVerbResult = validator.validate(badVerb, null)
        assertTrue(badVerbResult is ValidationResult.Failure)
        assertEquals("TRUNCATE_NOT_ALLOWED", (badVerbResult as ValidationResult.Failure).code)

        val badPragmaResult = validator.validate(badPragma, null)
        assertTrue(badPragmaResult is ValidationResult.Failure)
        assertEquals("PRAGMA_NOT_ALLOWED", (badPragmaResult as ValidationResult.Failure).code)
    }

    @Test
    fun select_actions_are_rejected() = runTest {
        val patch = patchWithActions(SqlAction("SELECT * FROM t"))
        val validator = SelectActionValidator()
        val result = validator.validate(patch, null)
        assertTrue(result is ValidationResult.Failure)
        assertEquals("SELECT_NOT_ALLOWED", (result as ValidationResult.Failure).code)
    }

    // ---------- MultiStatementValidator ----------
    @Test
    fun multi_statement_rejects_multiple_statements_and_allows_single_trailing_semicolon() =
        runTest {
            val goodNoSemi = patchWithActions(SqlAction("UPDATE t SET x=1"))
            val goodTrailing = patchWithActions(SqlAction("UPDATE t SET x=1;"))
            val goodInQuotes = patchWithActions(SqlAction("UPDATE t SET note='a; b';"))
            val badMultiple = patchWithActions(SqlAction("UPDATE t SET x=1; DELETE FROM t;"))
            val badMultipleNoTrailing =
                patchWithActions(SqlAction("UPDATE t SET x=1; DELETE FROM t"))

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
    fun hash_validator_passes_on_match_and_fails_on_mismatch_or_missing_bytes() = runTest {
        val jsonBytes = """{"k":"v"}""".encodeToByteArray()
        val expected = sha256Hex(jsonBytes)

        val pWithHash =
            patchWithActions(SqlAction("UPDATE t SET x=1"))
                .copy(metadata = mapOf("sha256" to expected))
        val pWithWrongHash =
            patchWithActions(SqlAction("UPDATE t SET x=1"))
                .copy(metadata = mapOf("sha256" to "deadbeef"))
        val pWithHashButNoBytes =
            patchWithActions(SqlAction("UPDATE t SET x=1"))
                .copy(metadata = mapOf("sha256" to expected))

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
