package dev.nathanmkaya.patchkit

import dev.nathanmkaya.patchkit.exec.ExecutionReport

/** Apply a patch given a JSON string (uses exact bytes of this string for hash verification). */
suspend fun PatchKit.apply(jsonString: String): ExecutionReport = apply(jsonString.encodeToByteArray())

/** Apply multiple patches in order. Returns one report per patch. */
suspend fun PatchKit.applyAll(jsonStrings: List<String>): List<ExecutionReport> = jsonStrings.map { apply(it) }
