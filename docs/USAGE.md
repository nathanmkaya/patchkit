# PatchKit Usage Guide

This guide walks through day-to-day usage of PatchKit—from loading a patch to monitoring outcomes. It assumes you’ve already added the dependency and read the high-level overview in the README.

## 1. Wire Up PatchKit

```kotlin
val config = PatchKitConfig(
    allowDDL = false,
    verifyHash = true,
    perActionTimeoutMs = 10_000,
    totalTimeoutMs = 60_000
)

val engineProvider = EngineProvider(connection = createSQLiteConnection(), busyTimeoutMs = 10_000)
val patchKit = PatchKit(registry = mapOf("main" to engineProvider), config = config)
```

### Engine Helpers
- `EngineProvider(connection, busyTimeoutMs)` wraps an existing `SQLiteConnection`.
- `EngineProvider(dbPath)` opens a file via `BundledSQLiteDriver`.
- Implement your own `EngineProvider` by supplying a `TransactionalEngine` (e.g., for SQLDelight).

## 2. Apply Patches

### From a JSON string or byte array
```kotlin
val report = patchKit.apply(patchJson)
if (report.success) {
    println("Applied ${report.patchId} in ${report.durationMs}ms")
} else {
    println("Failed: ${report.events.last().message}")
}
```

### Dry run (no commit, no idempotency write)
```kotlin
val dryRun = patchKit.apply(patchJson, dryRun = true)
check(dryRun.events.any { it.code == EventCode.DRYRUN_ROLLBACK })
```

### From files / directories
```kotlin
val reports = patchKit.applyDirectory(directory = myPath)     // Executes *.json alphabetically
val single = patchKit.applyPath(path = myFile, dryRun = true)  // Useful for validation workflows
```

## 3. Understand Execution Reports

`ExecutionReport` exposes:
- `patchId`, `durationMs`, `affectedRows`
- `events`: chronologically ordered `ExecutionEvent`s with `EventCode`, timestamp, message, and detail map.

Common codes:
- `VALIDATION_FAIL` – validator short-circuit (size, hash, DML allowlist, etc.).
- `IDEMPOTENT_SKIP` – patch was already recorded as successful (skipped in dry-run mode).
- `PRECHECK_*`, `ACTION_*`, `POSTCHECK_*` – phase-by-phase status.
- `TX_BEGIN`, `TX_COMMIT`, `TX_ROLLBACK`, `DRYRUN_ROLLBACK` – transaction lifecycle.

Use `report.pretty()` in logs for a human-friendly timeline.

## 4. Safety Checklist Before Production
- ✅ Run a dry run to confirm validations and postconditions.
- ✅ Ensure hashes (`metadata.sha256`) match the patch artifact.
- ✅ Confirm `allowDDL = true` only when the patch actually needs DDL.
- ✅ Keep actions single-statement and DML-focused (INSERT/UPDATE/DELETE/REPLACE/WITH).
- ✅ Verify postconditions cover the intended state changes.
- ✅ Monitor reports for unexpected `ACTION_FAIL` or `TX_ROLLBACK` events.

## 5. Customisation Hooks
- **Validators** – register custom `PatchValidator` implementations to enforce domain rules (e.g., forbid certain tables).
- **Idempotency** – provide a custom `IdempotencyManager` to store records elsewhere.
- **IO** – use or extend `applyPath` / `applyDirectory` for CLI or batch workflows.

## 6. Testing & Tooling
- Host-side: `./gradlew :patchkit-core:allTests`
- Android instrumentation: `./gradlew :patchkit-core:connectedAndroidTest`
- Refer to `docs/VALIDATION_AND_SAFETY.md` for validator specifics and operational policies.

With these patterns you can integrate PatchKit into CI/CD pipelines, ops tooling, or in-app migration flows while preserving the guardrails that keep production data safe.
