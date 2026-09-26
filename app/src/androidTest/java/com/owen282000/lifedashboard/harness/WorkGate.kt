package com.owen282000.lifedashboard.harness

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Decides which workers the test WorkManager may really run. Closed by default: every worker
 * is replaced by one that does nothing.
 *
 * Needed because the application schedules both sources at start on a background executor,
 * which can land in the test WorkManager after [AppStateRule] cancelled everything, and the
 * test WorkManager runs periodic work without a delay at once: the first run of the suite had
 * a real HealthSyncWorker syncing next to the test's own sync. A test about the scheduling
 * chain opens the gate for the duration of that test; [AppStateRule] closes it again.
 */
object WorkGate : WorkerFactory() {

    @Volatile
    var open: Boolean = false

    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
        if (open) return null // WorkManager's default: the real worker, by reflection
        Log.i("LdSuite", "WorkGate kept $workerClassName from running")
        return Parked(appContext, workerParameters)
    }

    class Parked(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }
}
