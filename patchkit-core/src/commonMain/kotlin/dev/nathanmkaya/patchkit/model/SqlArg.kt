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
 * Typed SQL arguments for cross-platform determinism.
 * NOTE: These are serialized in JSON with the same "type" discriminator as actions.
 * We avoid inline/value classes here because we use polymorphic serialization.
 */
@Serializable
sealed interface SqlArg {
    @Serializable
    @SerialName("Null")
    data object Null : SqlArg

    @Serializable
    @SerialName("Text")
    data class Text(
        val v: String,
    ) : SqlArg

    @Serializable
    @SerialName("Int64")
    data class Int64(
        val v: Long,
    ) : SqlArg

    @Serializable
    @SerialName("Real")
    data class Real(
        val v: Double,
    ) : SqlArg

    @Serializable
    @SerialName("Blob")
    data class Blob(
        @Serializable(with = ByteArrayAsBase64Serializer::class)
        val v: ByteArray,
    ) : SqlArg
}

/**
 * Base64 (RFC 4648) serializer for ByteArray so that Blobs are strings in JSON.
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
