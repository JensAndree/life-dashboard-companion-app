package com.owen282000.lifedashboard.harness

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID

/** The test WorkManager as the scheduling tests see it: unique work by name, and the driver. */
object Work {

    private val context get() = TestSetup.context

    val driver: TestDriver get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)) { "no test WorkManager" }

    fun infos(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    fun enqueued(name: String): List<WorkInfo> = infos(name).filter { it.state == WorkInfo.State.ENQUEUED }

    fun info(id: UUID): WorkInfo? = WorkManager.getInstance(context).getWorkInfoById(id).get()

    /** The one ENQUEUED work under [name], waiting for the scheduler's executor to put it there. */
    fun awaitEnqueued(name: String, timeoutMs: Long = 10_000, besides: UUID? = null): WorkInfo {
        Await.until("work $name to be enqueued", timeoutMs) { enqueued(name).any { it.id != besides } }
        return enqueued(name).single { it.id != besides }
    }

    fun awaitState(id: UUID, vararg states: WorkInfo.State, timeoutMs: Long = 30_000): WorkInfo {
        Await.until("work $id to reach ${states.toList()}", timeoutMs) { info(id)?.state in states }
        return info(id)!!
    }

    const val HEALTH_PERIODIC = "health_sync_work"
    const val HEALTH_SLOT_A = "health_sync_work_next_a"
    const val HEALTH_SLOT_B = "health_sync_work_next_b"
    const val TAG_SCHEDULED = "lifedashboard.scheduled"
    const val TAG_SLOT_A = "lifedashboard.slot.a"
    const val TAG_SLOT_B = "lifedashboard.slot.b"
}
