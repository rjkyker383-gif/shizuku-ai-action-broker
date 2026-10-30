package com.rjkyker.shizukuai.broker.autonomy

import com.rjkyker.shizukuai.broker.autonomy.queue.TaskState
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskStateMachine
import com.rjkyker.shizukuai.broker.autonomy.scheduler.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    @Test fun terminalStatesNeverTransition() {
        for (from in TaskState.values().filter { it.terminal }) for (to in TaskState.values())
            assertFalse("$from->$to", TaskStateMachine.canTransition(from, to))
    }

    @Test fun happyPathAndRetry() {
        assertTrue(TaskStateMachine.canTransition(TaskState.QUEUED, TaskState.RUNNING))
        assertTrue(TaskStateMachine.canTransition(TaskState.RUNNING, TaskState.VERIFYING))
        assertTrue(TaskStateMachine.canTransition(TaskState.VERIFYING, TaskState.SUCCEEDED))
        assertTrue(TaskStateMachine.canTransition(TaskState.RUNNING, TaskState.QUEUED))
        assertTrue(TaskStateMachine.canTransition(TaskState.VERIFYING, TaskState.NEEDS_USER))
        assertFalse(TaskStateMachine.canTransition(TaskState.QUEUED, TaskState.SUCCEEDED))
        assertFalse(TaskStateMachine.canTransition(TaskState.NEEDS_USER, TaskState.RUNNING))
    }

    @Test fun backoffDoublesAndCaps() {
        assertEquals(30_000L, TaskStateMachine.backoffMs(1))
        assertEquals(60_000L, TaskStateMachine.backoffMs(2))
        assertEquals(120_000L, TaskStateMachine.backoffMs(3))
        assertEquals(TaskStateMachine.MAX_BACKOFF_MS, TaskStateMachine.backoffMs(50))
    }

    @Test fun retriesUntilMaxAttemptsThenFails() {
        assertEquals(TaskState.QUEUED, TaskStateMachine.afterFailure(1, 3, true))
        assertEquals(TaskState.FAILED, TaskStateMachine.afterFailure(3, 3, true))
        assertEquals(TaskState.FAILED, TaskStateMachine.afterFailure(1, 3, false))
    }

    private fun snap(auto: Boolean = true, paused: Boolean = false, thermal: Int = 0, bat: Int = 80, charging: Boolean = false, saver: Boolean = false) =
        Conditions.Snapshot(auto, paused, thermal, bat, charging, saver)

    @Test fun schedulerGates() {
        assertTrue(Conditions.evaluate(snap(), false) is Conditions.Decision.Run)
        assertTrue(Conditions.evaluate(snap(auto = false), false) is Conditions.Decision.Wait)
        assertTrue(Conditions.evaluate(snap(paused = true), false) is Conditions.Decision.Wait)
        assertTrue(Conditions.evaluate(snap(thermal = 2), false) is Conditions.Decision.Wait)
        assertTrue(Conditions.evaluate(snap(thermal = 1), true) is Conditions.Decision.Wait)
        assertTrue(Conditions.evaluate(snap(thermal = 1, charging = true), true) is Conditions.Decision.Run)
        assertTrue(Conditions.evaluate(snap(bat = 10), false) is Conditions.Decision.Wait)
        assertTrue(Conditions.evaluate(snap(bat = 25), true) is Conditions.Decision.Wait)
        assertTrue(Conditions.evaluate(snap(bat = 25), false) is Conditions.Decision.Run)
        assertTrue(Conditions.evaluate(snap(bat = 10, charging = true), true) is Conditions.Decision.Run)
        assertTrue(Conditions.evaluate(snap(saver = true), true) is Conditions.Decision.Wait)
    }
}
