package dev.nathanmkaya.patchkit.io

import dev.nathanmkaya.patchkit.PatchKit
import dev.nathanmkaya.patchkit.exec.ExecutionReport
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Apply a patch from a filesystem path (JVM). */
suspend fun PatchKit.applyFile(path: Path): ExecutionReport = apply(SystemFileSystem.source(path).buffered().readByteArray())

/** Apply patches from multiple files, in order. */
suspend fun PatchKit.applyFiles(paths: List<Path>): List<ExecutionReport> = paths.map { applyFile(it) }
