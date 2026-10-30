package com.rjkyker.shizukuai.broker.autonomy.queue

enum class TaskState { QUEUED, WAITING_CONDITIONS, RUNNING, VERIFYING, NEEDS_USER, SUCCEEDED, FAILED, CANCELLED, EXPIRED;
    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED
    val active: Boolean get() = !terminal
}

/** Legal transitions (design doc state diagram). Pure; enforced by TaskQueue on every write. */
object TaskStateMachine {
    private val T = TaskState.values().associateWith { emptySet<TaskState>() }.toMutableMap().apply {
        put(TaskState.QUEUED, setOf(TaskState.WAITING_CONDITIONS, TaskState.RUNNING, TaskState.CANCELLED, TaskState.EXPIRED))
        put(TaskState.WAITING_CONDITIONS, setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.CANCELLED, TaskState.EXPIRED))
        put(TaskState.RUNNING, setOf(TaskState.VERIFYING, TaskState.SUCCEEDED, TaskState.FAILED, TaskState.QUEUED,
            TaskState.NEEDS_USER, TaskState.CANCELLED))
        put(TaskState.VERIFYING, setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.QUEUED, TaskState.NEEDS_USER, TaskState.CANCELLED))
        put(TaskState.NEEDS_USER, setOf(TaskState.QUEUED, TaskState.SUCCEEDED, TaskState.CANCELLED, TaskState.EXPIRED, TaskState.FAILED))
    }

    fun canTransition(from: TaskState, to: TaskState): Boolean = to in (T[from] ?: emptySet())

    const val BASE_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 30 * 60_000L

    /** Exponential backoff for retry n (1-based): 30 s, 60 s, 120 s ... capped at 30 min. */
    fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** After a failed run: retry (back to QUEUED) while attempts remain, else FAILED. */
    fun afterFailure(attempts: Int, maxAttempts: Int, retryable: Boolean): TaskState =
        if (retryable && attempts < maxAttempts) TaskState.QUEUED else TaskState.FAILED
}
