package dev.nathanmkaya.patchkit

import dev.nathanmkaya.patchkit.model.ComparisonOperator
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchKitJsonTest {
    @Test
    fun decode_minimal_sql_action() {
        val json =
            """
            {
              "version": 1,
              "id": "example-20250101-001",
              "target": "main",
              "actions": [
                { "type": "SqlAction", "sql": "UPDATE users SET active = 1" }
              ],
              "metadata": { "sha256": "deadbeef" }
            }
            """.trimIndent()

        val patch = PatchKitJson.strict.decodeFromString<Patch>(json)

        assertEquals(1, patch.version)
        assertEquals("example-20250101-001", patch.id)
        assertEquals("main", patch.target)
        assertEquals(1, patch.actions.size)
        val act = patch.actions.first()
        assertTrue(act is SqlAction)
        assertEquals("UPDATE users SET active = 1", (act as SqlAction).sql)
        assertEquals("deadbeef", patch.metadata["sha256"])
    }

    @Test
    fun decode_parameterized_action_with_typed_params() {
        val json =
            """
            {
              "version": 1,
              "id": "example-20250101-002",
              "target": "main",
              "actions": [
                {
                  "type": "ParameterizedSqlAction",
                  "sql": "UPDATE users SET flag = ?, score = ?, data = ? WHERE id = ?",
                  "parameters": [
                    { "type": "Text",  "v": "vip" },
                    { "type": "Real",  "v":  12.5 },
                    { "type": "Blob",  "v":  "AQIDBA==" },
                    { "type": "Int64", "v":  42 }
                  ]
                }
              ]
            }
            """.trimIndent()

        val patch = PatchKitJson.strict.decodeFromString<Patch>(json)
        val act = patch.actions.single() as ParameterizedSqlAction
        assertEquals(4, act.parameters.size)

        val p0 = act.parameters[0] as SqlArg.Text
        val p1 = act.parameters[1] as SqlArg.Real
        val p2 = act.parameters[2] as SqlArg.Blob
        val p3 = act.parameters[3] as SqlArg.Int64

        assertEquals("vip", p0.v)
        assertEquals(12.5, p1.v)
        // Blob is base64("01 02 03 04") = "AQIDBA=="
        assertTrue(p2.v.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(42L, p3.v)
    }

    @Test
    fun encode_contains_discriminator_and_base64_for_blob() {
        val patch =
            Patch(
                version = 1,
                id = "encode-blob-1",
                target = "main",
                actions =
                    listOf(
                        ParameterizedSqlAction(
                            sql = "UPDATE t SET b = ?",
                            parameters =
                                listOf(
                                    SqlArg.Blob(byteArrayOf(1, 2, 3, 4)),
                                ),
                        ),
                        SqlAction("UPDATE t SET x = 1"),
                    ),
            )

        val encoded = PatchKitJson.strict.encodeToString(patch)

        // Discriminators present
        assertTrue(encoded.contains("\"type\":\"ParameterizedSqlAction\""), encoded)
        assertTrue(encoded.contains("\"type\":\"SqlAction\""), encoded)

        // Blob encoded as base64 string "AQIDBA=="
        assertTrue(encoded.contains("\"AQIDBA==\""), encoded)
        // Also ensure SqlArg subtype discriminator is emitted
        assertTrue(encoded.contains("\"type\":\"Blob\""), encoded)
    }

    @Test
    fun defaults_on_condition_operator_are_applied() {
        val json =
            """
            {
              "version": 1,
              "id": "example-conditions-1",
              "target": "main",
              "preconditions": [
                { "sql": "SELECT COUNT(*) FROM t", "expected": 0 }
              ],
              "actions": [ { "type": "SqlAction", "sql": "UPDATE t SET x = 1" } ]
            }
            """.trimIndent()

        val patch = PatchKitJson.strict.decodeFromString<Patch>(json)
        val cond = patch.preconditions.single()
        assertEquals(ComparisonOperator.EQUALS, cond.operator, "Default operator should be EQUALS")
        assertEquals(0L, cond.expected)
    }

    @Test
    fun unknown_keys_are_rejected_due_to_strict_json() {
        val json =
            """
            {
              "version": 1,
              "id": "strict-unknown-1",
              "target": "main",
              "bogus": 123,
              "actions": [ { "type": "SqlAction", "sql": "UPDATE t SET x = 1" } ]
            }
            """.trimIndent()

        assertFailsWith<SerializationException> {
            PatchKitJson.strict.decodeFromString<Patch>(json)
        }
    }

    @Test
    fun invalid_version_is_rejected_by_model_guard() {
        val json =
            """
            {
              "version": 2,
              "id": "bad-version",
              "target": "main",
              "actions": [ { "type": "SqlAction", "sql": "UPDATE t SET x = 1" } ]
            }
            """.trimIndent()

        // The data class init { require(version == 1) } should throw IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            PatchKitJson.strict.decodeFromString<Patch>(json)
        }
    }

    @Test
    fun round_trip_without_blob_compares_structurally() {
        val original =
            Patch(
                version = 1,
                id = "roundtrip-1",
                target = "main",
                metadata = mapOf("sha256" to "cafebabe"),
                actions =
                    listOf(
                        SqlAction("UPDATE a SET x = 1"),
                        ParameterizedSqlAction(
                            sql = "UPDATE a SET name = ? WHERE id = ?",
                            parameters = listOf(SqlArg.Text("alice"), SqlArg.Int64(7)),
                        ),
                    ),
            )

        val json = PatchKitJson.strict.encodeToString(original)
        val decoded = PatchKitJson.strict.decodeFromString<Patch>(json)

        // Compare key fields (avoid deep equals pitfalls if Blob is present)
        assertEquals(original.id, decoded.id)
        assertEquals(original.target, decoded.target)
        assertEquals(original.metadata["sha256"], decoded.metadata["sha256"])
        assertEquals(original.actions.size, decoded.actions.size)

        val a0 = decoded.actions[0] as SqlAction
        val a1 = decoded.actions[1] as ParameterizedSqlAction
        assertEquals("UPDATE a SET x = 1", a0.sql)

        val p0 = a1.parameters[0] as SqlArg.Text
        val p1 = a1.parameters[1] as SqlArg.Int64
        assertEquals("alice", p0.v)
        assertEquals(7L, p1.v)
    }
}
