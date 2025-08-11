package dev.nathanmkaya.patchkit.report

import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.exec.ExecutionReport

/**
 * Human-friendly rendering of an ExecutionReport.
 * Keeps machine-readable data in code/detail but shows a clean timeline.
 */
fun ExecutionReport.pretty(includeDetails: Boolean = true): String {
    val b = StringBuilder()
    b.appendLine("PatchKit Report")
    b.appendLine("  patchId   : $patchId")
    b.appendLine("  success   : $success")
    b.appendLine("  duration  : $durationMs ms")
    b.appendLine("  rows      : $affectedRows")
    b.appendLine("  events    : ${events.size}")
    b.appendLine("  timeline:")
    val t0 = startTime
    for (e in events) {
        val dt = e.ts - t0
        val line =
            buildString {
                append("   - [+$dt ms] ${e.code}: ${e.message}")
                if (includeDetails && e.detail.isNotEmpty()) {
                    append("  ")
                    append(e.detail.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k=$v" })
                }
            }
        b.appendLine(line)
    }
    // quick hint for common end states
    if (!success && events.lastOrNull()?.code == EventCode.IDEMPOTENT_SKIP) {
        b.appendLine("  note      : already applied (idempotent skip)")
    }
    return b.toString()
}
