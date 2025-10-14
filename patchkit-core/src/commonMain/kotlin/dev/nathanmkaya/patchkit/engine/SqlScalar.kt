package dev.nathanmkaya.patchkit.engine

import kotlin.jvm.JvmInline

/**
 * Engine-returned scalar value for simple SELECTs (e.g., COUNT(*)). Not serialized; this is purely
 * runtime-facing.
 */
sealed interface SqlScalar {
    data object Null : SqlScalar

    @JvmInline value class Int64(val v: Long) : SqlScalar

    @JvmInline value class Real(val v: Double) : SqlScalar

    @JvmInline value class Text(val v: String) : SqlScalar

    data class Blob(val v: ByteArray) : SqlScalar
}

/**
 * Require a numeric scalar; throws if the value is null/non-numeric. Use a descriptive label to
 * make errors self-explanatory in logs.
 */
fun SqlScalar?.requireLong(label: String): Long =
    when (this) {
        is SqlScalar.Int64 -> v
        is SqlScalar.Real -> v.toLong()
        is SqlScalar.Text ->
            v.toLongOrNull() ?: error("Condition '$label' must return a numeric value, got TEXT")
        null,
        SqlScalar.Null,
        is SqlScalar.Blob ->
            error(
                "Condition '$label' must return a numeric value, got ${this?.let { it::class.simpleName } ?: "null"}"
            )
    }
