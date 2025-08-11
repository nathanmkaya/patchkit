package dev.nathanmkaya.patchkit.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/** Multiplatform SHA-256 → lowercase hex via cryptography-kotlin. */
suspend fun sha256Hex(bytes: ByteArray): String {
    // Provider is selected per platform by the "optimal" dependency
    val hasher =
        CryptographyProvider.Default
            .get(SHA256)
            .hasher()

    val digest: ByteArray = hasher.hash(bytes)
    return digest.toHexLower()
}

private fun ByteArray.toHexLower(): String {
    val out = CharArray(size * 2)
    var i = 0
    for (b in this) {
        val v = b.toInt() and 0xff
        out[i++] = HEX[v ushr 4]
        out[i++] = HEX[v and 0x0f]
    }
    return out.concatToString()
}

private val HEX = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
