package dev.nathanmkaya.patchkit.engine

import kotlin.jvm.JvmInline

/**
 * Engine-returned scalar value for simple SELECTs (e.g., COUNT(*)).
 * Not serialized; this is purely runtime-facing.
 */
sealed interface SqlScalar {
    data object Null : SqlScalar

    @JvmInline
    value class Int64(
        val v: Long,
    ) : SqlScalar

    @JvmInline
    value class Real(
        val v: Double,
    ) : SqlScalar

    @JvmInline
    value class Text(
        val v: String,
    ) : SqlScalar

    data class Blob(
        val v: ByteArray,
    ) : SqlScalar
}

/** Convenience coercions for numeric comparisons in conditions. */
fun SqlScalar?.asLongOrZero(): Long =
    when (this) {
        is SqlScalar.Int64 -> v
        is SqlScalar.Real -> v.toLong()
        is SqlScalar.Text -> v.toLongOrNull() ?: 0L
        else -> 0L
    }
