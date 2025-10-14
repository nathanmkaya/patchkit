# Validation & Safety Playbook

This document explains how PatchKit enforces safety before, during, and after patch execution. Use it as a reference when authoring patches or extending the library.

## Validators (Pre-execution)
PatchKit builds a validator chain at startup:

| Validator | What it Does | Failure Code |
|-----------|--------------|--------------|
| `SizeValidator` | Rejects patches above `maxBytes` or with more than `maxActions`. | `SIZE_EXCEEDED`, `TOO_MANY_ACTIONS` |
| `MultiStatementValidator` | Ensures each action is a single statement (no extra semicolons outside strings). | `MULTI_STATEMENT` |
| `HashValidator` | Verifies `metadata.sha256` against the original bytes when `verifyHash = true`. | `HASH_MISMATCH`, `HASH_MISSING_BYTES` |
| `DmlOnlyValidator` | Allows only INSERT/UPDATE/DELETE/REPLACE/WITH and whitelisted PRAGMAs (`foreign_keys`, `defer_foreign_keys`, `busy_timeout`). | `VERB_NOT_ALLOWED`, `PRAGMA_NOT_ALLOWED` |
| `SelectActionValidator` | Blocks `SELECT` as the first verb in an action. | `SELECT_NOT_ALLOWED` |

Add custom validators by implementing `PatchValidator` and injecting them ahead of PatchKit construction.

## Idempotency Guarantees
- Default `TableBasedIdempotency` persists `_patchkit_applied(patch_id, applied_at, metadata)`.
- Idempotency is skipped when `dryRun = true` so exploratory runs leave no trace.
- Provide your own `IdempotencyManager` to store metadata in files, caches, or remote services.

## Execution Safeguards
1. **Preconditions / Postconditions** – run as SQL scalar checks. The helper `SqlScalar.requireLong` throws when a condition returns non-numeric results so failures are explicit.
2. **Transactional Execution** – actions run inside a single IMMEDIATE transaction. `SqliteEngineAdapter` also applies `PRAGMA busy_timeout = 5000` (override via `EngineProvider`).
3. **Timeouts** –
   - `perActionTimeoutMs` wraps each action in `withTimeout`.
   - `totalTimeoutMs` bounds the overall execution window.
   - Native drivers can still block a thread; combine short actions with busy timeouts to bound risk.
4. **Dry Run Mode** – set `dryRun = true` when calling `PatchKit.apply(...)` or the IO helpers. Actions execute inside a savepoint and finish with event `DRYRUN_ROLLBACK`. Idempotency writes are skipped.

## Event Timeline & Reporting
Key `EventCode`s to monitor:
- `VALIDATION_FAIL`, `IDEMPOTENT_SKIP`
- `PRECHECK_*`, `ACTION_*`, `POSTCHECK_*`
- `TX_BEGIN`, `TX_COMMIT`, `TX_ROLLBACK`
- `DRYRUN_ROLLBACK`

Consume the `ExecutionReport` timeline to drive logs, metrics, and alerting. The `report.pretty()` helper formats the sequence for troubleshooting.

## Testing Strategy
- Host tests (`src/commonTest`) verify validators, engine adapters, dry-run behaviour, and timeout signalling.
- Android instrumentation tests (`src/androidDeviceTest`) run against real SQLite to cover DDL policies, idempotency, IO helpers, and dry runs.
- Use `./gradlew :patchkit-core:allTests` for unit coverage and `./gradlew :patchkit-core:connectedAndroidTest` for device-level safety checks.

## Authoring Patches Safely
- Start with a dry run to confirm validations and postconditions pass.
- Keep actions granular; prefer parameterised statements (`ParameterizedSqlAction`) to avoid SQL injection and enable precise auditing.
- Provide descriptive `preconditions` and `postconditions`—they become user-facing messages in the event log when a check fails.
- Include `metadata.sha256` (and, optionally, additional metadata) so HashValidator can catch tampering.
