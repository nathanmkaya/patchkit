package dev.nathanmkaya.patchkit.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Strongly-typed SQL parameter arguments for safe cross-platform execution.
 *
 * SqlArg provides type-safe parameter binding for parameterized SQL statements,
 * ensuring consistent behavior across different platforms and databases. Each type
 * maps to the appropriate SQL data type during execution.
 *
 * These are serialized in JSON with a "type" discriminator field, allowing
 * explicit type specification in patch definitions.
 *
 * ## Example JSON representations:
 * ```json
 * { "type": "Null" }
 * { "type": "Text", "v": "Hello World" }
 * { "type": "Int64", "v": 42 }
 * { "type": "Real", "v": 3.14159 }
 * { "type": "Blob", "v": "SGVsbG8gV29ybGQ=" }
 * ```
 */
@Serializable
sealed interface SqlArg {
    /** SQL NULL value */
    @Serializable
    @SerialName("Null")
    data object Null : SqlArg

    /**
     * String/text parameter value.
     *
     * @param v The string value
     */
    @Serializable
    @SerialName("Text")
    data class Text(
        val v: String,
    ) : SqlArg

    /**
     * 64-bit integer parameter value.
     *
     * @param v The long integer value
     */
    @Serializable
    @SerialName("Int64")
    data class Int64(
        val v: Long,
    ) : SqlArg

    /**
     * Double precision floating-point parameter value.
     *
     * @param v The double value
     */
    @Serializable
    @SerialName("Real")
    data class Real(
        val v: Double,
    ) : SqlArg

    /**
     * Binary data parameter value.
     *
     * The byte array is serialized as Base64-encoded string in JSON for portability.
     *
     * @param v The binary data as byte array
     */
    @Serializable
    @SerialName("Blob")
    data class Blob(
        @Serializable(with = ByteArrayAsBase64Serializer::class)
        val v: ByteArray,
    ) : SqlArg
}

/**
 * Custom serializer for ByteArray that encodes/decodes as Base64 strings in JSON.
 *
 * This allows binary data to be safely represented in JSON patches while maintaining
 * cross-platform compatibility. Uses RFC 4648 Base64 encoding.
 */
object ByteArrayAsBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    @OptIn(ExperimentalEncodingApi::class)
    override fun deserialize(decoder: Decoder): ByteArray {
        val s = decoder.decodeString()
        return Base64.decode(s)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun serialize(
        encoder: Encoder,
        value: ByteArray,
    ) {
        val s = Base64.encode(value)
        encoder.encodeString(s)
    }
}
