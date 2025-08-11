package dev.nathanmkaya.patchkit.io

import dev.nathanmkaya.patchkit.PatchKit
import dev.nathanmkaya.patchkit.exec.ExecutionReport
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Apply a patch from a file path (multiplatform, via kotlinx-io). */
suspend fun PatchKit.applyPath(
    path: Path,
    fs: FileSystem = SystemFileSystem,
): ExecutionReport {
    val raw = fs.source(path)
    val bytes =
        raw.buffered().use { source ->
            source.readByteArray()
        }
    return apply(bytes)
}

/** Apply every *.json file in a directory, sorted by filename. */
suspend fun PatchKit.applyDirectory(
    directory: Path,
    fs: FileSystem = SystemFileSystem,
    extension: String = ".json",
): List<ExecutionReport> {
    val entries =
        fs
            .list(directory)
            .filter { p -> p.toString().endsWith(extension) }
            .sortedBy { it.toString() }

    val reports = mutableListOf<ExecutionReport>()
    for (p in entries) {
        reports += applyPath(p, fs)
    }
    return reports
}
