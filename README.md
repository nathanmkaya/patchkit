# PatchKit

A robust Kotlin Multiplatform library for executing SQL database patches with transaction safety, comprehensive validation, and idempotency guarantees.

## Overview

PatchKit provides a secure, reliable way to apply database schema and data migrations through JSON-defined patches. It's designed for production environments where data integrity, auditability, and operational safety are paramount.

### Key Features

- **Transactional Safety**: All patches execute within ACID transactions with automatic rollback on failure
- **Comprehensive Validation**: Multi-layered validation including size limits, DDL restrictions, and hash verification
- **Idempotency Guarantees**: Prevents duplicate patch application through pluggable idempotency management
- **Cross-Platform**: Kotlin Multiplatform support for Android, iOS, and JVM
- **Detailed Auditing**: Complete execution timeline with machine-readable event codes
- **Timeout Management**: Per-action and total execution timeouts to prevent runaway operations
- **Security-First**: Configurable restrictions on dangerous SQL operations

## Architecture

```
┌─────────────┐    ┌─────────────────┐    ┌──────────────────┐
│   PatchKit  │────│  PatchExecutor  │────│ TransactionalEngine │
│(Orchestrator)│    │  (Execution)    │    │   (Database)     │
└─────────────┘    └─────────────────┘    └──────────────────┘
       │                     │
       ├─ Validators         ├─ EventCode Timeline
       ├─ IdempotencyManager └─ Timeout Management
       └─ JSON Deserialization
```

### Core Components

- **PatchKit**: Main orchestrator handling validation → idempotency → execution → recording
- **PatchExecutor**: Manages transactional execution with preconditions/postconditions
- **TransactionalEngine**: Database abstraction with SQLite implementation
- **Validators**: Pluggable validation chain (size, hash, DDL restrictions, etc.)
- **IdempotencyManager**: Prevents duplicate patch execution

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

### 3. Define Patches

```json
{
  "version": 1,
  "id": "add-user-table-v1",
  "target": "main",
  "description": "Create users table with indexes",
  "preconditions": [
    {
      "sql": "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='users'",
      "operator": "EQUALS",
      "expected": 0,
      "description": "Users table should not exist"
    }
  ],
  "actions": [
    {
      "type": "SqlAction",
      "sql": "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE NOT NULL)",
      "description": "Create users table"
    },
    {
      "type": "ParameterizedSqlAction",
      "sql": "INSERT INTO users (email) VALUES (?)",
      "parameters": [
        { "type": "Text", "v": "admin@example.com" }
      ],
      "description": "Create admin user"
    }
  ],
  "postconditions": [
    {
      "sql": "SELECT COUNT(*) FROM users",
      "operator": "EQUALS", 
      "expected": 1,
      "description": "Should have one user"
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
    val report = patchKit.apply(patchJson.encodeToByteArray())
    
    if (report.success) {
        println("✅ Patch ${report.patchId} applied successfully")
        println("📊 Affected ${report.affectedRows} rows in ${report.durationMs}ms")
    } else {
        println("❌ Patch failed: ${report.events.last().message}")
    }
    
    return report
}
```

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

## Configuration

### Security Settings

```kotlin
PatchKitConfig(
    allowDDL = false,              // Block CREATE/ALTER/DROP operations
    maxBytes = 1_000_000,          // Maximum patch size in bytes  
    maxActions = 500,              // Maximum actions per patch
    verifyHash = true,             // Require SHA-256 hash verification
    checksInReadTx = false         // Run conditions outside transactions
)
```

### Timeout Configuration

```kotlin
PatchKitConfig(
    perActionTimeoutMs = 30_000,   // Timeout per individual action
    totalTimeoutMs = 300_000,      // Total patch execution timeout
)
```

### Custom Idempotency

```kotlin
class CustomIdempotencyManager : IdempotencyManager {
    override suspend fun hasBeenApplied(patchId: String, engine: SqliteEngine): Boolean {
        // Custom logic to check if patch was applied
    }
    
    override suspend fun recordApplication(patchId: String, engine: SqliteEngine, metadata: String?) {
        // Custom logic to record successful application  
    }
}

val config = PatchKitConfig(idempotency = CustomIdempotencyManager())
```

## Execution Flow

1. **Parse**: Deserialize JSON patch with strict validation
2. **Validate**: Run validation chain (size, hash, DDL restrictions, etc.)
3. **Idempotency Check**: Skip if patch already applied
4. **Preconditions**: Verify database state before execution
5. **Execute Actions**: Run all actions in single IMMEDIATE transaction
6. **Postconditions**: Validate resulting database state
7. **Record Success**: Mark patch as applied for future idempotency

## Event System

PatchKit provides detailed execution reporting through structured events:

```kotlin
enum class EventCode {
    VALIDATION_FAIL, IDEMPOTENT_SKIP, TX_BEGIN, TX_COMMIT,
    PRECHECK_START, PRECHECK_OK, PRECHECK_FAIL,
    ACTION_START, ACTION_OK, ACTION_FAIL, 
    POSTCHECK_START, POSTCHECK_OK, POSTCHECK_FAIL,
    PATCH_SUCCESS, PATCH_FAILURE
}
```

### Example Event Timeline

```kotlin
val report = patchKit.apply(patch)
report.events.forEach { event ->
    println("${event.ts} [${event.code}] ${event.message}")
}
```

## Validation Chain

PatchKit includes several built-in validators:

- **SizeValidator**: Enforces maximum patch size and action count
- **HashValidator**: Verifies SHA-256 hash in metadata
- **DmlOnlyValidator**: Blocks DDL operations when `allowDDL = false`
- **MultiStatementValidator**: Prevents multiple statements per action

## Database Integration

### SQLite Integration

```kotlin
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.nathanmkaya.patchkit.driver.SqliteEngineAdapter

val driver = BundledSQLiteDriver()
val connection = driver.open("database.db")
val engine = SqliteEngineAdapter(connection)

val patchKit = PatchKit(
    registry = mapOf("main" to EngineProvider { engine })
)
```

### Custom Engine Implementation

```kotlin
class CustomEngine : TransactionalEngine {
    override suspend fun queryScalar(sql: String, args: List<SqlArg>): SqlScalar? {
        // Execute query and return first column of first row
    }
    
    override suspend fun execute(sql: String, args: List<SqlArg>): Int {
        // Execute DML and return affected row count
    }
    
    override suspend fun <T> inTransaction(immediate: Boolean, block: suspend () -> T): T {
        // Manage transaction boundary with COMMIT/ROLLBACK
    }
}
```

## Error Handling

### Execution Report

```kotlin
data class ExecutionReport(
    val patchId: String,
    val events: List<ExecutionEvent>,
    val startTime: Long,
    val endTime: Long, 
    val affectedRows: Int = 0
) {
    val durationMs: Long = endTime - startTime
    val success: Boolean = events.any { it.code == EventCode.PATCH_SUCCESS }
}
```

### Common Failure Scenarios

- **Validation Failure**: Patch violates size limits or security policies
- **Idempotent Skip**: Patch already applied successfully  
- **Precondition Failure**: Database not in expected state
- **Action Failure**: SQL execution error or timeout
- **Postcondition Failure**: Database state doesn't match expectations
- **Transaction Failure**: Unexpected exception during execution

## Best Practices

### Patch Design

- **Keep patches small**: Limit actions to minimize transaction time
- **Use preconditions**: Verify assumptions about database state
- **Add postconditions**: Validate expected outcomes
- **Include descriptions**: Document purpose of each action
- **Version control**: Store patches in VCS with meaningful names

### Security

- **Restrict DDL**: Use `allowDDL = false` in production
- **Limit size**: Set appropriate `maxBytes` and `maxActions`
- **Verify hashes**: Enable hash verification for integrity
- **Parameterize queries**: Prefer `ParameterizedSqlAction` over `SqlAction`
- **Audit events**: Log all execution reports for compliance

### Operations

- **Monitor timeouts**: Adjust timeout settings based on workload
- **Handle failures gracefully**: Implement retry logic with backoff
- **Test patches**: Validate patches in staging environments first  
- **Batch related changes**: Group logically related operations
- **Plan rollbacks**: Design reverse patches for critical changes

## Platform Support

- **Android**: Full support via AndroidX SQLite
- **iOS**: Full support via Native SQLite bindings
- **JVM**: Full support for server applications
- **JavaScript**: Planned (contributions welcome)

## Advanced Topics

### Custom Validators

```kotlin
class BusinessRuleValidator : PatchValidator {
    override suspend fun validate(patch: Patch, rawBytes: ByteArray?): ValidationResult {
        // Custom business logic validation
        return if (isValid(patch)) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure("BUSINESS_RULE", "Violates business constraints")
        }
    }
}

// Add to validation chain
val config = PatchKitConfig(/* ... */)
// Note: Custom validators require extending the validation chain
```

### Concurrent Execution

PatchKit is designed for single-threaded patch application per database. For concurrent scenarios:

- Use separate PatchKit instances per database
- Implement application-level coordination
- Consider patch dependencies and ordering

### Performance Tuning

- **Connection pooling**: Use appropriate SQLite connection management
- **Batch operations**: Group related SQL operations in single actions
- **Index optimization**: Ensure adequate indexes for condition queries
- **Timeout tuning**: Adjust timeouts based on operation complexity

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

```bash
git clone https://github.com/nathanmkaya/patchkit.git
cd patchkit
./gradlew build
```

## License

PatchKit is released under the [Apache 2.0 License](LICENSE).

---

## FAQ

**Q: Can patches be rolled back?**
A: PatchKit doesn't provide automatic rollback. Design reverse patches for critical changes that may need to be undone.

**Q: What happens if a patch partially succeeds?**
A: All actions execute within a single transaction. Either all actions succeed and commit, or the entire transaction rolls back on any failure.

**Q: Can I use PatchKit with databases other than SQLite?**
A: Currently SQLite-focused, but the `TransactionalEngine` interface allows custom implementations for other databases.

**Q: How do I handle schema migrations vs data migrations?**
A: Use `allowDDL = true` for schema patches and `allowDDL = false` for data-only patches. Consider separate patch workflows for each type.

**Q: Is PatchKit production-ready?**
A: Yes, PatchKit is designed for production use with comprehensive validation, transaction safety, and detailed auditing. The GPT-5 review confirms solid architecture with some recommended enhancements for high-concurrency scenarios.