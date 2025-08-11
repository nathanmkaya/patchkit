package dev.nathanmkaya.patchkit.engine

import dev.nathanmkaya.patchkit.model.SqlArg

/**
 * Simple programmable engine for tests:
 * - Queue scalar results for queryScalar
 * - Queue ints for execute
 * - Records invocations and args
 */
class FakeEngine : TransactionalEngine {
    private val scalarQueue = ArrayDeque<SqlScalar?>()
    private val executeQueue = ArrayDeque<Int>()

    val queries = mutableListOf<Pair<String, List<SqlArg>>>()
    val executions = mutableListOf<Pair<String, List<SqlArg>>>()

    var beganImmediate = 0
    var beganDeferred = 0
    var committed = 0
    var rolledBack = 0

    fun queueScalar(result: SqlScalar?) {
        scalarQueue.addLast(result)
    }

    fun queueExecute(affected: Int) {
        executeQueue.addLast(affected)
    }

    override suspend fun queryScalar(
        sql: String,
        args: List<SqlArg>,
    ): SqlScalar? {
        queries += sql to args
        return if (scalarQueue.isEmpty()) SqlScalar.Null else scalarQueue.removeFirst()
    }

    override suspend fun execute(
        sql: String,
        args: List<SqlArg>,
    ): Int {
        executions += sql to args
        return if (executeQueue.isEmpty()) 0 else executeQueue.removeFirst()
    }

    override suspend fun <T> inTransaction(
        immediate: Boolean,
        block: suspend () -> T,
    ): T {
        if (immediate) beganImmediate++ else beganDeferred++
        return try {
            val result = block()
            committed++
            result
        } catch (t: Throwable) {
            rolledBack++
            throw t
        }
    }
}
