package dev.nathanmkaya.patchkit.io

import dev.nathanmkaya.patchkit.PatchKit
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.exec.ExecutionEvent
import dev.nathanmkaya.patchkit.exec.ExecutionReport
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** Apply a patch from a file path (uses Okio; works on JVM/Android/Native/JS). */
suspend fun PatchKit.applyPath(
    path: Path,
    fs: FileSystem = FileSystem.SYSTEM,
): ExecutionReport {
    val meta = fs.metadata(path)
    require(meta.isRegularFile) { "Path is not a regular file: $path" }

    val bytes = fs.read(path) { readByteArray() }

    if (bytes.isEmpty() || bytes.decodeToString().isBlank()) {
        return ExecutionReport.EMPTY(
            ExecutionEvent(
                0,
                EventCode.VALIDATION_FAIL,
                "Empty patch input",
                detail = mapOf("code" to "EMPTY_INPUT", "path" to path.toString()),
            ),
        )
    }
    return apply(bytes)
}

/** Apply every file with the given extension in a directory (sorted by filename). */
suspend fun PatchKit.applyDirectory(
    directory: Path,
    fs: FileSystem = FileSystem.SYSTEM,
    extension: String = ".json",
): List<ExecutionReport> {
    val meta = fs.metadata(directory)
    require(meta.isDirectory) { "Not a directory: $directory" }

    val files =
        fs
            .list(directory)
            .filter { p -> fs.metadata(p).isRegularFile && p.name.endsWith(extension) }
            .sortedBy { it.name }

    val reports = mutableListOf<ExecutionReport>()
    for (p in files) {
        reports += applyPath(p, fs)
    }
    return reports
}
