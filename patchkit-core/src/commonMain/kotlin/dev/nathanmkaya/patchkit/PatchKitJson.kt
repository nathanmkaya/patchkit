package dev.nathanmkaya.patchkit

import kotlinx.serialization.json.Json

/**
 * Centralized Json configuration for PatchKit.
 * - Strict (no unknown keys)
 * - Emits defaults
 * - Uses "type" as the polymorphic discriminator
 */
object PatchKitJson {
    val strict: Json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            classDiscriminator = "type"
        }
}
