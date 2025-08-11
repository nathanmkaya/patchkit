package dev.nathanmkaya.patchkit.exec

/** Machine-readable event codes for auditability. */
enum class EventCode {
    VALIDATION_FAIL,
    VERIFICATION_FAIL,
    IDEMPOTENT_SKIP,
    TX_BEGIN,
    TX_COMMIT,
    TX_ROLLBACK,
    PRECHECK_START,
    PRECHECK_OK,
    PRECHECK_FAIL,
    ACTION_START,
    ACTION_OK,
    ACTION_FAIL,
    POSTCHECK_START,
    POSTCHECK_OK,
    POSTCHECK_FAIL,
    PATCH_SUCCESS,
    PATCH_FAILURE,
}

/** Timeline event emitted by the executor. */
data class ExecutionEvent(
    val ts: Long,
    val code: EventCode,
    val message: String,
    val detail: Map<String, String> = emptyMap(),
)

/** Final run summary. */
data class ExecutionReport(
    val patchId: String,
    val events: List<ExecutionEvent>,
    val startTime: Long,
    val endTime: Long,
    val affectedRows: Int = 0,
) {
    val durationMs: Long get() = endTime - startTime
    val success: Boolean get() = events.any { it.code == EventCode.PATCH_SUCCESS }

    companion object {
        fun EMPTY(vararg events: ExecutionEvent) = ExecutionReport("", events.toList(), 0, 0, 0)
    }
}
