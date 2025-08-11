package dev.nathanmkaya.patchkit

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.nathanmkaya.patchkit.driver.EngineProvider
import dev.nathanmkaya.patchkit.exec.EventCode
import dev.nathanmkaya.patchkit.io.applyDirectory
import dev.nathanmkaya.patchkit.report.pretty
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportAndIoTest {
    private val tmpDir = createTempDirMp()

    @Test
    fun apply_from_string_and_file_and_pretty_print() =
        runTest {
            // DB setup (bundled driver)
            val dbPath = (createTempDirectory() / "test.db")
            val conn = BundledSQLiteDriver().open(dbPath.toString())
            conn.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, active INTEGER DEFAULT 0)")
            conn.execSQL("INSERT INTO users(id, name, active) VALUES (1, 'Alice', 0)")
            conn.execSQL("INSERT INTO users(id, name, active) VALUES (2, 'Bob', 0)")

            val kit =
                PatchKit(
                    registry = mapOf("main" to EngineProvider(conn)),
                    config = PatchKitConfig(verifyHash = false),
                )

            // ---- Write two patches
            val dir = createTempDirectory(prefix = "patches-")
            val p1 = dir / "001-activate.json"
            val p2 = dir / "002-rename.json"

            val json1 =
                """
                {
                  "version": 1,
                  "id": "activate-1",
                  "target": "main",
                  "actions": [
                    { "type": "SqlAction", "sql": "UPDATE users SET active = 1 WHERE id = 1" }
                  ],
                  "postconditions": [
                    { "sql": "SELECT COUNT(*) FROM users WHERE active = 1", "expected": 1 }
                  ]
                }
                """.trimIndent()
            writeTextFile(p1, json1)

            val json2 =
                """
                {
                  "version": 1,
                  "id": "rename-1",
                  "target": "main",
                  "actions": [
                    { "type": "SqlAction", "sql": "UPDATE users SET name = 'Bobby' WHERE id = 2" }
                  ],
                  "postconditions": [
                    { "sql": "SELECT COUNT(*) FROM users WHERE name = 'Bobby'", "expected": 1 }
                  ]
                }
                """.trimIndent()
            writeTextFile(p2, json2)

            // ---- Run directory
            val reports = kit.applyDirectory(dir)
            assertEquals(2, reports.size)
            assertTrue(reports.all { it.success })
            assertTrue(reports.first().events.any { it.code == EventCode.TX_BEGIN })

            // Pretty print first report (manual check)
            val out = reports.first().pretty()
            assertTrue(out.contains("PatchKit Report"))

            // ---- Re-run to confirm idempotent skip
            val again = kit.applyDirectory(dir)
            assertTrue(again.all { !it.success && it.events.any { e -> e.code == EventCode.IDEMPOTENT_SKIP } })
        }

    private fun writeText(
        path: Path,
        contents: String,
    ) {
        SystemFileSystem.source(path)
        SystemFileSystem.sink(path, append = false).buffered().use { buf ->
            buf.writeString(contents) // UTF-8 by default
        }
    }

    fun createTempDirMp(
        fs: FileSystem = SystemFileSystem,
        base: Path = Path("build", "tmp"),
        prefix: String = "patchkit-",
    ): Path {
        val random = (1..6).joinToString("") { ('a'..'z').random().toString() }
        val dir = Path(base.toString(), "$prefix$random")
        fs.createDirectories(base)
        fs.createDirectories(dir)
        return dir
    }

    /** Write a UTF-8 text file (overwrites by default). */
    private fun writeTextFile(
        path: okio.Path,
        text: String,
        fs: okio.FileSystem = okio.FileSystem.SYSTEM,
        append: Boolean = false,
    ) {
        if (!append) {
            // Ensure parent directories exist.
            fs.createDirectories(path.parent ?: ".".toPath())
        }
        fs.write(path, mustCreate = false) {
            writeUtf8(text)
        }
    }

    /** Create a throwaway directory like build/tmp/patchkit-abc123 (multiplatform). */
    private fun createTempDirectory(
        base: okio.Path = "build/tmp".toPath(),
        prefix: String = "patchkit-",
        fs: okio.FileSystem = okio.FileSystem.SYSTEM,
    ): okio.Path {
        fs.createDirectories(base)
        val dir = base / (prefix + randomHex(6))
        fs.createDirectories(dir)
        return dir
    }

    private fun randomHex(n: Int): String = buildString(n) { repeat(n) { append("0123456789abcdef"[Random.nextInt(16)]) } }
}
