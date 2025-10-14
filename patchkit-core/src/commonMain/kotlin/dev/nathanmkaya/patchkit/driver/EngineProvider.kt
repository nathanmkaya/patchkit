package dev.nathanmkaya.patchkit.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.nathanmkaya.patchkit.engine.EngineProvider

/** Create an EngineProvider from an already-opened connection. */
fun EngineProvider(connection: SQLiteConnection, busyTimeoutMs: Long = 5_000): EngineProvider =
    EngineProvider {
        SqliteEngineAdapter(connection, busyTimeoutMs)
    }

/** Create an EngineProvider that opens (or creates) a DB file via BundledSQLiteDriver. */
fun EngineProvider(dbPath: String, busyTimeoutMs: Long = 5_000): EngineProvider = EngineProvider {
    val connection = BundledSQLiteDriver().open(dbPath)
    SqliteEngineAdapter(connection, busyTimeoutMs)
}
