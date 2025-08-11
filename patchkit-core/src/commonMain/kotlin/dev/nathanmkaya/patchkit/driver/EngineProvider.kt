package dev.nathanmkaya.patchkit.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.nathanmkaya.patchkit.engine.EngineProvider

/** Create an EngineProvider from an already-opened connection. */
fun EngineProvider(connection: SQLiteConnection): EngineProvider = EngineProvider { SqliteEngineAdapter(connection) }

/** Create an EngineProvider that opens (or creates) a DB file via BundledSQLiteDriver. */
fun EngineProvider(dbPath: String): EngineProvider = EngineProvider { SqliteEngineAdapter(BundledSQLiteDriver().open(dbPath)) }
