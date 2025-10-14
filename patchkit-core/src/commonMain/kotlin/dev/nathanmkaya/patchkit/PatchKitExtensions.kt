package dev.nathanmkaya.patchkit

import dev.nathanmkaya.patchkit.exec.ExecutionReport

/** Apply a patch given a JSON string (uses exact bytes of this string for hash verification). */
suspend fun PatchKit.apply(
    jsonString: String,
    dryRun: Boolean = false,
): ExecutionReport = apply(jsonString.encodeToByteArray(), dryRun)

/** Apply multiple patches in order. Returns one report per patch. */
suspend fun PatchKit.applyAll(
    jsonStrings: List<String>,
    dryRun: Boolean = false,
): List<ExecutionReport> = jsonStrings.map { apply(it, dryRun) }
