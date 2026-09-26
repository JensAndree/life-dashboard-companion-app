package com.owen282000.lifedashboard.harness

import android.app.job.JobScheduler
import androidx.health.connect.client.HealthConnectClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthSyncWorker
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.SyncScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the harness itself before any sync test leans on it: WorkManager is the test
 * instance, what the app schedules lands there, and the real JobScheduler
 * holds nothing of the app that could fire during a test.
 */
@RunWith(AndroidJUnit4::class)
class HarnessSelfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun workManagerIsTheTestInstance() {
        assertNotNull("the runner did not install the test WorkManager", WorkManagerTestInitHelper.getTestDriver(context))
        // What the app schedules lands in the test instance (on the scheduler's own executor).
        SyncScheduler.reschedule(context, LogType.HEALTH_CONNECT)
        val enqueued = Await.until("the app's schedule in the test WorkManager") {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(HealthSyncWorker.WORK_NAME).get()
                .any { it.state == WorkInfo.State.ENQUEUED }
        }
        assertTrue(enqueued)
    }

    @Test
    fun noRealJobIsPending() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        Await.until("the real WorkManager to drop its JobScheduler jobs") { scheduler.allPendingJobs.isEmpty() }
    }

    /** A validator that accepts everything would make every schema assertion in the suite worthless. */
    @Test
    fun schemaRejectsAPayloadWithoutAppVersion() {
        val valid = """{"timestamp":"2026-09-26T10:00:00Z","app_version":"1.20.0","source":"health_connect"}"""
        assertEquals(emptyList<String>(), Schema.errors(valid, "selftest-valid"))
        val invalid = """{"timestamp":"2026-09-26T10:00:00Z","source":"health_connect"}"""
        assertTrue(Schema.errors(invalid, "selftest-invalid").any { "app_version" in it })
    }

    /**
     * Seam 1 end to end: the app's own HealthConnectManager, handed a client that holds the
     * permission lookup, hangs until its caller gives up, and gives up promptly.
     */
    @Test
    fun aHeldHealthConnectCallHangsUntilCancelled() = runBlocking {
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        slow.held += HcCall.GRANTED_PERMISSIONS
        val manager = HealthConnectManager(context) { slow }
        val started = System.currentTimeMillis()
        val answer = withTimeoutOrNull(500) { manager.getGrantedPermissions() }
        val took = System.currentTimeMillis() - started
        assertNull("the held call must not answer", answer)
        assertTrue("cancelled after $took ms", took < 2_000)
        slow.held.clear()
        assertNotNull(withTimeoutOrNull(10_000) { manager.getGrantedPermissions() })
    }

    /** The suite's HMAC code against the vectors of the Home Assistant integration's tests. */
    @Test
    fun hmacMatchesTheIntegrationVectors() {
        Hmac.check()
    }
}
