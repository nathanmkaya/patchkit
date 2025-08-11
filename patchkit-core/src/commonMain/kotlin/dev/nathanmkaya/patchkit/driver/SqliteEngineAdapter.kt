package dev.nathanmkaya.patchkit.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import dev.nathanmkaya.patchkit.engine.SqlScalar
import dev.nathanmkaya.patchkit.engine.TransactionalEngine
import dev.nathanmkaya.patchkit.model.SqlArg

/**
 * TransactionalEngine backed by AndroidX SQLite KMP.
 * Uses a pre-opened SQLiteConnection (from BundledSQLiteDriver or any driver).
 *
 * Notes:
 * - queryScalar reads the first column as TEXT, then parses to Int64/Real when possible.
 *   This keeps behavior consistent across platforms without relying on getColumnType().
 * - execute() returns affected row count via SELECT changes().
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
