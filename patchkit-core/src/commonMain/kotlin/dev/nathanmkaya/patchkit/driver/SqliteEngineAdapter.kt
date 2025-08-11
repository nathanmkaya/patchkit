package dev.nathanmkaya.patchkit.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import dev.nathanmkaya.patchkit.engine.SqlScalar
import dev.nathanmkaya.patchkit.engine.TransactionalEngine
import dev.nathanmkaya.patchkit.model.SqlArg

/**
 * Implementation of TransactionalEngine using AndroidX SQLite KMP.
 *
 * This adapter provides cross-platform SQLite database access through the AndroidX SQLite
 * KMP library, supporting Android, iOS, and JVM platforms with consistent behavior.
 *
 * ## Key Features:
 * - **Cross-platform compatibility**: Works on Android, iOS, and JVM through AndroidX SQLite KMP
 * - **Consistent type handling**: Reads columns as text and parses to appropriate types
 * - **Transaction safety**: Proper BEGIN/COMMIT/ROLLBACK handling with immediate mode support
 * - **Parameter binding**: Safe parameterized query support with strongly-typed arguments
 *
 * ## Implementation Notes:
 * - `queryScalar` reads the first column as TEXT, then attempts parsing to Int64/Real for
 *   consistent behavior across platforms without relying on driver-specific column type detection
 * - `execute` returns affected row count via `SELECT changes()` following SQLite semantics
 * - Connection should be thread-confined for SQLite safety requirements
 *
 * ## Usage:
 * ```kotlin
 * val driver = BundledSQLiteDriver()
 * val connection = driver.open("database.db")
 * val engine = SqliteEngineAdapter(connection)
 * 
 * // Use with PatchKit
 * val patchKit = PatchKit(
 *     registry = mapOf("main" to EngineProvider { engine })
 * )
 * ```
 *
 * @param connection Pre-opened SQLiteConnection from any AndroidX SQLite KMP driver
 */
class SqliteEngineAdapter(
    private val connection: SQLiteConnection,
) : TransactionalEngine {
    override suspend fun queryScalar(
        sql: String,
        args: List<SqlArg>,
    ): SqlScalar? {
        connection.prepare(sql).use { stmt ->
            bindArgs(stmt, args)
            val hasRow = stmt.step()
            if (!hasRow) return SqlScalar.Null
            // Read as text, parse numeric if possible (robust across drivers)
            val text = stmt.getText(0) ?: return SqlScalar.Null
            text.toLongOrNull()?.let { return SqlScalar.Int64(it) }
            text.toDoubleOrNull()?.let { return SqlScalar.Real(it) }
            return SqlScalar.Text(text)
        }
    }

    override suspend fun execute(
        sql: String,
        args: List<SqlArg>,
    ): Int {
        // Execute the statement
        connection.prepare(sql).use { stmt ->
            bindArgs(stmt, args)
            // For DML/DDL, a single step is sufficient.
            stmt.step()
        }
        // Report affected rows
        connection.prepare("SELECT changes()").use { stmt ->
            return if (stmt.step()) stmt.getInt(0) else 0
        }
    }

    override suspend fun <T> inTransaction(
        immediate: Boolean,
        block: suspend () -> T,
    ): T {
        // Mirrors SQLite docs; BEGIN IMMEDIATE is our default per RFC.
        connection.execSQL(if (immediate) "BEGIN IMMEDIATE" else "BEGIN")
        return try {
            val result = block()
            connection.execSQL("COMMIT")
            result
        } catch (t: Throwable) {
            connection.execSQL("ROLLBACK")
            throw t
        }
    }

    private fun bindArgs(
        stmt: SQLiteStatement,
        args: List<SqlArg>,
    ) {
        // 1-based indices in SQLite
        args.forEachIndexed { idx, arg ->
            val i = idx + 1
            when (arg) {
                is SqlArg.Null -> stmt.bindNull(i)
                is SqlArg.Text -> stmt.bindText(i, arg.v)
                is SqlArg.Int64 -> stmt.bindLong(i, arg.v)
                is SqlArg.Real -> stmt.bindDouble(i, arg.v)
                is SqlArg.Blob -> stmt.bindBlob(i, arg.v)
            }
        }
    }
}
