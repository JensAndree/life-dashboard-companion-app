package com.owen282000.lifedashboard.harness

/** Polling with a deadline, for state that settles on another thread. Never a bare sleep. */
object Await {

    /** Polls [condition] every [intervalMs] until it holds; fails naming [what] after [timeoutMs]. */
    fun until(what: String, timeoutMs: Long = 10_000, intervalMs: Long = 50, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        if (condition()) return true
        throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
    }
}
