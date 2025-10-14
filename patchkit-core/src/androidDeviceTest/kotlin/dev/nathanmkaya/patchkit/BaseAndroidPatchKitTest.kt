package dev.nathanmkaya.patchkit

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import dev.nathanmkaya.patchkit.driver.EngineProvider
import dev.nathanmkaya.patchkit.exec.ExecutionReport
import dev.nathanmkaya.patchkit.io.applyDirectory
import dev.nathanmkaya.patchkit.io.applyPath
import dev.nathanmkaya.patchkit.model.Action
import dev.nathanmkaya.patchkit.model.Condition
import dev.nathanmkaya.patchkit.model.ParameterizedSqlAction
import dev.nathanmkaya.patchkit.model.Patch
import dev.nathanmkaya.patchkit.model.SqlAction
import dev.nathanmkaya.patchkit.model.SqlArg
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before

abstract class BaseAndroidPatchKitTest {
    private lateinit var appContext: Context
    private lateinit var dbFile: File
    protected lateinit var connection: SQLiteConnection
    private val driver = BundledSQLiteDriver()
    private val tempPaths = mutableSetOf<File>()

    @Before
    fun baseSetUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = File(appContext.cacheDir, "patchkit-integration.db").apply { delete() }
        connection = driver.open(dbFile.absolutePath)
        connection.execSQL("PRAGMA foreign_keys = ON")
        resetSchema()
        tempPaths.clear()
    }

    @After
    fun baseTearDown() {
        runCatching { connection.close() }
        tempPaths.forEach { file -> runCatching { file.deleteRecursively() } }
        dbFile.delete()
    }

    protected suspend fun applyPatch(
        patchJson: String,
        config: PatchKitConfig = PatchKitConfig(),
        dryRun: Boolean = false,
    ): ExecutionReport = patchKit(config).apply(patchJson, dryRun)

    private fun patchKit(config: PatchKitConfig = PatchKitConfig()): PatchKit =
        PatchKit(mapOf("main" to EngineProvider(connection)), config)

    protected fun patchJson(
        id: String,
        preconditions: List<Condition> = emptyList(),
        actions: List<Action>,
        postconditions: List<Condition> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        target: String = "main",
    ): String =
        PatchKitJson.strict.encodeToString(
            Patch.serializer(),
            Patch(
                id = id,
                target = target,
                preconditions = preconditions,
                actions = actions,
                postconditions = postconditions,
                metadata = metadata,
            ),
        )

    protected fun insertAccount(name: String, balance: Int) =
        ParameterizedSqlAction(
            sql = "INSERT INTO accounts(name, balance) VALUES (?, ?)",
            parameters = listOf(SqlArg.Text(name), SqlArg.Int64(balance.toLong())),
            description = "Insert account $name",
        )

    protected fun conditionCount(table: String, expected: Long, label: String) =
        Condition(sql = "SELECT COUNT(*) FROM $table", expected = expected, description = label)

    protected fun conditionTableAbsent(table: String) =
        Condition(
            sql = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'",
            expected = 0,
            description = "Table $table must be absent",
        )

    protected fun conditionTableExists(table: String) =
        Condition(
            sql = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'",
            expected = 1,
            description = "Table $table must exist",
        )

    protected fun sql(sql: String) = SqlAction(sql)

    protected fun accountCount(): Int = tableRowCount("accounts")

    protected fun accountNames(): List<String> {
        connection.prepare("SELECT name FROM accounts ORDER BY account_id").use { stmt ->
            val names = mutableListOf<String>()
            while (stmt.step()) {
                names += stmt.getText(0) ?: ""
            }
            return names
        }
    }

    protected fun tableExists(name: String): Boolean {
        connection
            .prepare("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")
            .use { stmt ->
                stmt.bindText(1, name)
                return stmt.step() && stmt.getInt(0) > 0
            }
    }

    protected fun tableRowCount(table: String): Int {
        connection.prepare("SELECT COUNT(*) FROM $table").use { stmt ->
            return if (stmt.step()) stmt.getInt(0) else 0
        }
    }

    protected fun ensureIdempotencyTable() {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS _patchkit_applied (
                patch_id TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL,
                metadata TEXT
            )
            """
                .trimIndent()
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx__patchkit_applied_patch_id ON _patchkit_applied(patch_id)"
        )
    }

    protected fun insertIdempotencyRecord(id: String, metadata: String = "{preseeded=true}") {
        ensureIdempotencyTable()
        connection
            .prepare(
                "INSERT OR IGNORE INTO _patchkit_applied(patch_id, applied_at, metadata) VALUES(?, ?, ?)"
            )
            .use { stmt ->
                stmt.bindText(1, id)
                stmt.bindLong(2, System.currentTimeMillis())
                stmt.bindText(3, metadata)
                stmt.step()
            }
    }

    protected fun idempotencyRecord(id: String): String? {
        ensureIdempotencyTable()
        connection.prepare("SELECT metadata FROM _patchkit_applied WHERE patch_id = ?").use { stmt
            ->
            stmt.bindText(1, id)
            return if (stmt.step()) stmt.getText(0) else null
        }
    }

    protected fun querySingle(sql: String): QueryRow {
        connection.prepare(sql).use { stmt ->
            check(stmt.step()) { "Expected at least one row" }
            val text = stmt.getText(0)
            val int = stmt.getLong(1)
            val real = stmt.getDouble(2)
            val blob = stmt.getBlob(3)
            return QueryRow(text, int, real, blob)
        }
    }

    protected fun writePatchFile(file: File, contents: String): Path {
        val path = file.absolutePath.toPath()
        FileSystem.SYSTEM.write(path) { writeUtf8(contents) }
        trackPath(path)
        return path
    }

    protected fun createTempDir(name: String): File {
        val dir =
            File(appContext.cacheDir, name).apply {
                deleteRecursively()
                mkdirs()
            }
        trackPath(dir.absolutePath.toPath())
        return dir
    }

    protected fun trackPath(path: Path) {
        tempPaths += File(path.toString())
    }

    protected suspend fun applyDirectory(
        path: Path,
        config: PatchKitConfig = PatchKitConfig(),
    ): List<ExecutionReport> = patchKit(config).applyDirectory(path)

    protected suspend fun applyPath(
        path: Path,
        config: PatchKitConfig = PatchKitConfig(),
    ): ExecutionReport = patchKit(config).applyPath(path)

    protected data class QueryRow(
        val text: String?,
        val int: Long?,
        val real: Double?,
        val blob: ByteArray?,
    )

    private fun resetSchema() {
        val drops =
            listOf("orders", "customers", "accounts", "blobs", "labels", "_patchkit_applied")
        drops.forEach { table -> connection.execSQL("DROP TABLE IF EXISTS $table") }
        connection.execSQL(
            """
            CREATE TABLE accounts (
                account_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                balance INTEGER NOT NULL
            )
            """
                .trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE customers (
                customer_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )
            """
                .trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE orders (
                order_id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                FOREIGN KEY(customer_id) REFERENCES customers(customer_id)
            )
            """
                .trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE blobs (
                sample_id INTEGER PRIMARY KEY AUTOINCREMENT,
                text_col TEXT,
                int_col INTEGER,
                real_col REAL,
                blob_col BLOB
            )
            """
                .trimIndent()
        )
    }
}
