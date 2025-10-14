# PatchKit Architecture Overview

PatchKit is a Kotlin Multiplatform (KMP) library that applies JSON-described database patches with strong safety guarantees. The system is composed of tightly scoped layers that work the same way on Android, iOS, JVM, and desktop hosts.

## Key Components

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
- **PatchKit** – the primary façade. It receives raw patch bytes, runs validation, manages idempotency, and orchestrates execution through a transactional engine.
- **PatchKitConfig** – feature toggles and safety limits (DDL policy, size/action caps, timeouts, hash verification, idempotency manager, JSON settings).
- **Validators** – pluggable chain that rejects unsafe payloads. Built-ins cover size, multi-statement detection, hash verification, DML allowlist + PRAGMA guard, and SELECT-action blocking.
- **PatchExecutor** – drives the runtime flow:
  1. Precondition checks (optional read transactions)
  2. Single IMMEDIATE transaction for all actions
  3. Optional dry-run savepoint (rolls back at the end)
  4. Postcondition checks
  5. Event emission & reporting
- **TransactionalEngine** – minimal SQL + transaction API used by the executor. `SqliteEngineAdapter` backs it with AndroidX SQLite KMP, while adapters can be added for other drivers.
- **IdempotencyManager** – records successful patch IDs to prevent replays. Default implementation stores rows in `_patchkit_applied`.
- **Execution Events** – structured timeline (`EventCode`) that tracks validation, actions, transaction boundaries, dry-run rollbacks, and failure causes. The resulting `ExecutionReport` powers auditing and logs.

## Execution Flow (Happy Path)
```
raw bytes → PatchKit
    ↳ decode JSON (strict)
    ↳ validators → failure? stop
    ↳ resolve engine (idempotency init)
    ↳ executor.execute()
          ↳ preconditions → failure? rollback
          ↳ IMMEDIATE tx { actions }
          ↳ postconditions → failure? rollback
    ↳ record success (unless dryRun)
```

## Platform Integration
- **Android** – uses `BundledSQLiteDriver` plus instrumentation tests under `src/androidDeviceTest`.
- **iOS & Desktop** – reuse the same adapter through Kotlin/Native binaries.
- **Host/JVM Testing** – `src/commonTest` uses bundled SQLite and FakeEngine helpers.

## Extensibility Points
- Provide custom `PatchValidator` implementations to enforce domain rules.
- Swap `IdempotencyManager` for external storage or cross-service coordination.
- Implement `TransactionalEngine` for alternative SQL backends (e.g., SQLDelight drivers).
- Observe `ExecutionReport` events to integrate with monitoring/alerting.

## Safety Defaults
- DDL is blocked unless `allowDDL = true`.
- Multi-statement payloads and SELECT actions are rejected.
- Hash verification (SHA-256) is enabled by default.
- `SqliteEngineAdapter` sets `PRAGMA busy_timeout = 5000` to avoid immediate lock contention.
- Dry-run mode executes full validation/postconditions without committing changes.
