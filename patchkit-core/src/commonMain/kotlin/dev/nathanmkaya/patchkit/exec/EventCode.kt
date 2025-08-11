package dev.nathanmkaya.patchkit.exec

/**
 * Machine-readable event codes for detailed patch execution auditing.
 *
 * These codes provide a complete timeline of patch execution phases, enabling
 * programmatic analysis of execution flow, error detection, and performance monitoring.
 * Each code represents a specific point in the execution lifecycle.
 */
enum class EventCode {
    /** Patch failed validation before execution (e.g., size limits, DDL restrictions) */
    VALIDATION_FAIL,
    /** Patch failed integrity verification (e.g., hash mismatch) */
    VERIFICATION_FAIL,
    /** Patch was skipped because it was already applied (idempotency check) */
    IDEMPOTENT_SKIP,
    /** Database transaction started */
    TX_BEGIN,
    /** Database transaction committed successfully */
    TX_COMMIT,
    /** Database transaction rolled back due to error */
    TX_ROLLBACK,
    /** Started checking preconditions */
    PRECHECK_START,
    /** A precondition check passed */
    PRECHECK_OK,
    /** A precondition check failed */
    PRECHECK_FAIL,
    /** Started executing an action */
    ACTION_START,
    /** An action executed successfully */
    ACTION_OK,
    /** An action execution failed */
    ACTION_FAIL,
    /** Started checking postconditions */
    POSTCHECK_START,
    /** A postcondition check passed */
    POSTCHECK_OK,
    /** A postcondition check failed */
    POSTCHECK_FAIL,
    /** Entire patch executed successfully */
    PATCH_SUCCESS,
    /** Patch execution failed */
    PATCH_FAILURE,
}

/**
 * A single event in the patch execution timeline.
 *
 * Events are emitted throughout patch execution to provide detailed visibility
 * into the execution process. They include timing information, event classification,
 * human-readable messages, and additional structured details.
 *
 * @param ts Timestamp when the event occurred (epoch milliseconds)
 * @param code Machine-readable event classification
 * @param message Human-readable description of what happened
 * @param detail Additional structured information about the event (e.g., row counts, error details)
 */
data class ExecutionEvent(
    val ts: Long,
    val code: EventCode,
    val message: String,
    val detail: Map<String, String> = emptyMap(),
)

/**
 * Complete report of patch execution including timeline, results, and performance metrics.
 *
 * This report provides comprehensive information about patch execution, including
 * a complete event timeline, success/failure status, affected row counts, and
 * performance metrics. It serves as both an audit record and execution result.
 *
 * ## Usage:
 * ```kotlin
 * val report = patchKit.apply(patchBytes)
 * 
 * if (report.success) {
 *     println("Patch ${report.patchId} applied successfully")
 *     println("Affected ${report.affectedRows} rows in ${report.durationMs}ms")
 * } else {
 *     println("Patch failed: ${report.events.last().message}")
 * }
 * 
 * // Audit logging
 * report.events.forEach { event ->
 *     logger.info("${event.ts} [${event.code}] ${event.message}")
 * }
 * ```
 *
 * @param patchId Unique identifier of the patch that was executed
 * @param events Complete timeline of execution events in chronological order
 * @param startTime Execution start timestamp (epoch milliseconds)
 * @param endTime Execution end timestamp (epoch milliseconds)
 * @param affectedRows Total number of database rows affected by the patch
 */
data class ExecutionReport(
    val patchId: String,
    val events: List<ExecutionEvent>,
    val startTime: Long,
    val endTime: Long,
    val affectedRows: Int = 0,
) {
    /** Execution duration in milliseconds */
    val durationMs: Long get() = endTime - startTime
    
    /** Whether the patch executed successfully (contains PATCH_SUCCESS event) */
    val success: Boolean get() = events.any { it.code == EventCode.PATCH_SUCCESS }

    companion object {
        /**
         * Create an empty report for testing purposes.
         *
         * @param events Optional events to include in the empty report
         * @return ExecutionReport with empty/default values
         */
        fun EMPTY(vararg events: ExecutionEvent) = ExecutionReport("", events.toList(), 0, 0, 0)
    }
}
