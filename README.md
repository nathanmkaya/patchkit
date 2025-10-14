# PatchKit

A robust Kotlin Multiplatform library for executing SQL database patches with transaction safety, comprehensive validation, and idempotency guarantees.

## Overview

PatchKit provides a secure, reliable way to apply database schema and data migrations through JSON-defined patches. It's designed for production environments where data integrity, auditability, and operational safety are paramount.

For a deeper look at system components and safety features, see the documents in `docs/`.

### Key Features

- **Transactional Safety**: All patches execute within ACID transactions with automatic rollback on failure
- **Comprehensive Validation**: Multi-layered validation including size limits, DDL restrictions, and hash verification
- **Idempotency Guarantees**: Prevents duplicate patch application through pluggable idempotency management
- **Cross-Platform**: Kotlin Multiplatform support for Android, iOS, and JVM
- **Detailed Auditing**: Complete execution timeline with machine-readable event codes
- **Timeout Management**: Per-action and total execution timeouts to prevent runaway operations
- **Security-First**: Configurable restrictions on dangerous SQL operations

## Quick Start

### 1. Add Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.nathanmkaya.patchkit:patchkit-core:1.0.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0") // For SQLite support
}
```

### 2. Configure PatchKit

```kotlin
val config = PatchKitConfig(
    allowDDL = false,              // Restrict to DML operations only
    maxBytes = 512_000,            // Max patch size
    maxActions = 200,              // Max actions per patch
    verifyHash = true,             // Enable hash verification
    perActionTimeoutMs = 10_000,   // Per-action timeout
    totalTimeoutMs = 60_000        // Total patch timeout
)

val patchKit = PatchKit(
    registry = mapOf("main" to EngineProvider { createEngine() }),
    config = config
)
```

`SqliteEngineAdapter` applies `PRAGMA busy_timeout = 5000` by default; pass `EngineProvider(connection, busyTimeoutMs = ...)` to tune it per deployment. Timeouts in `PatchKitConfig` are best-effort (native calls can block); keep actions short and rely on busy timeouts to bound lock contention.

### 3. Define Patches

```json
{
  "version": 1,
  "id": "activate-users-v1",
  "target": "main",
  "description": "Activate and name users",
  "preconditions": [
    {
      "sql": "SELECT COUNT(*) FROM users WHERE active = 0",
      "operator": "GREATER_THAN",
      "expected": 0,
      "description": "There are inactive users"
    }
  ],
  "actions": [
    {
      "type": "SqlAction",
      "sql": "UPDATE users SET active = 1 WHERE id = 1",
      "description": "Activate primary user"
    },
    {
      "type": "ParameterizedSqlAction",
      "sql": "UPDATE users SET name = ? WHERE id = ?",
      "parameters": [
        { "type": "Text", "v": "Admin" },
        { "type": "Int64", "v": 1 }
      ],
      "description": "Rename primary user"
    }
  ],
  "postconditions": [
    {
      "sql": "SELECT COUNT(*) FROM users WHERE active = 1",
      "operator": "GREATER_OR_EQUAL",
      "expected": 1,
      "description": "Primary user activated"
    }
  ],
  "metadata": {
    "author": "DevOps Team",
    "sha256": "abc123..."
  }
}
```

### 4. Apply Patches

```kotlin
suspend fun applyPatch(patchJson: String): ExecutionReport {
    val report = patchKit.apply(patchJson)

    if (report.success) {
        println("✅ Patch ${report.patchId} applied successfully")
        println("📊 Affected ${report.affectedRows} rows in ${report.durationMs}ms")
    } else {
        println("❌ Patch failed: ${report.events.last().message}")
    }

    return report
}
```

#### Dry-run a patch

```kotlin
val dryRunReport = patchKit.apply(patchJson, dryRun = true)
println(dryRunReport.success)            // false – dry runs never emit PATCH_SUCCESS
println(dryRunReport.events.last().code) // DRYRUN_ROLLBACK indicates the savepoint was rolled back
```

Dry runs skip idempotency initialization/recording, so use a full apply before promoting a patch to production.

## Patch Format

### Patch Structure

```kotlin
data class Patch(
    val version: Int = 1,                    // Format version
    val id: String,                          // Unique patch identifier
    val target: String,                      // Database target alias
    val description: String? = null,         // Human-readable description
    val preconditions: List<Condition>,      // Pre-execution checks
    val actions: List<Action>,               // SQL operations to perform
    val postconditions: List<Condition>,     // Post-execution validation
    val metadata: Map<String, String>        // Additional metadata
)
```

### Actions

**SqlAction** - Direct SQL execution:
```json
{
  "type": "SqlAction",
  "sql": "UPDATE users SET active = 1 WHERE created_at > '2024-01-01'",
  "description": "Activate recent users"
}
```

**ParameterizedSqlAction** - SQL with typed parameters:
```json
{
  "type": "ParameterizedSqlAction", 
  "sql": "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
  "parameters": [
    { "type": "Text", "v": "John Doe" },
    { "type": "Text", "v": "john@example.com" },
    { "type": "Int64", "v": 30 }
  ]
}
```

### Parameter Types

- `Null`: SQL NULL value
- `Text`: String values
- `Int64`: 64-bit integers  
- `Real`: Double precision floats
- `Blob`: Binary data (Base64 encoded in JSON)

### Conditions

```json
{
  "sql": "SELECT COUNT(*) FROM users WHERE active = 1", 
  "operator": "GREATER_THAN",
  "expected": 0,
  "description": "Must have active users"
}
```

**Operators**: `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_OR_EQUAL`, `LESS_THAN`, `LESS_OR_EQUAL`


## Further Reading

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) – high-level component map and execution flow.
- [docs/VALIDATION_AND_SAFETY.md](docs/VALIDATION_AND_SAFETY.md) – validator matrix, dry-run behaviour, and operational guardrails.
- [docs/USAGE.md](docs/USAGE.md) – practical workflow for loading and applying patches.
