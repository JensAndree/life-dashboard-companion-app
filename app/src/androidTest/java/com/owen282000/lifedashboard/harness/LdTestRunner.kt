package com.owen282000.lifedashboard.harness

import android.app.Application
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * The instrumentation runner of the suite. It puts WorkManager in test mode before
 * [com.owen282000.lifedashboard.LifeDashboardApplication.onCreate] schedules anything, so no
 * real sync worker can post to a test's receiver halfway through another test.
 *
 * By the time this runs, androidx.startup has already initialised the real WorkManager, and
 * that instance may hold work from an earlier start of the app (a periodic sync that is due
 * runs at once). So the real instance is told to cancel everything first, which also removes
 * its JobScheduler jobs, and then the test instance is installed as the delegate that every
 * `WorkManager.getInstance` call returns from here on. Its workers only run for real when a
 * test opens [WorkGate]; otherwise a worker that becomes due does nothing.
 *
 * Running any class of this APK on a device with a real setup therefore drops that setup's
 * scheduled syncs until the app next starts and reschedules them, which it does on its own.
 * The suite itself refuses such devices (scripts/instrumented.sh).
 */
class LdTestRunner : AndroidJUnitRunner() {

    override fun callApplicationOnCreate(app: Application) {
        WorkManager.getInstance(app).cancelAllWork()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(WorkGate)
                .build()
        )
        super.callApplicationOnCreate(app)
    }
}
