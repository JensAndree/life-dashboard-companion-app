package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.ScreenTimeUse
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A sync that is cancelled (a stopped worker, a timeout, a screen going away) must unwind
 * as a cancellation: no failure logged, no streak, no outbox item, nothing moved. The rule
 * that 1.18.0 taught, tested without WorkManager so only the sync code is in the way.
 */
@RunWith(AndroidJUnit4::class)
class CancellationTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()

    /**
     * T50. Cancelled while the receiver has not answered. Red on main: WebhookManager catches
     * the CancellationException thrown by its backoff delay and logs a failed delivery.
     */
    @Ignore("F1: fixed in phase 3")
    @Test
    fun performSyncPropagatesCancellation() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(55, ago(30), ago(20)))
        receiver.stall(TestSetup.HEALTH_PATH)
        val statusBefore = SyncStatusStore.read(context, LogType.HEALTH_CONNECT)

        val job = launch(Dispatchers.IO) { TestSetup.syncManager().performSync() }
        receiver.awaitRequests(1)
        val cancelledAt = System.currentTimeMillis()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("unwound within 15 s", System.currentTimeMillis() - cancelledAt < 15_000)
        val failures = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { !it.success }
        assertEquals("no failed delivery logged for a cancellation: ${failures.map { it.errorMessage }}", 0, failures.size)
        assertEquals(0, TestSetup.streak("HEALTH_CONNECT"))
        assertEquals(statusBefore, SyncStatusStore.read(context, LogType.HEALTH_CONNECT))
        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertEquals(null, prefs.getHealthLastSyncTimestamp(STEPS))

        // The lock is free again: the next sync delivers.
        receiver.respond(TestSetup.HEALTH_PATH, 200)
        val next = withTimeout(30_000) { HealthSyncManager(context).performSync().getOrThrow() }
        assertTrue(next is HealthSyncResult.Success)
    }

    /**
     * T51. The same for Screen Time, whose sync also catches everything. Red on main for the
     * same reason as T50, plus ScreenTimeSyncManager's own catch-all.
     */
    @Ignore("F1: fixed in phase 3")
    @Test
    fun screenTimeCancellation() = runBlocking {
        ScreenTimeUse.ensureToday()
        TestSetup.screenTime(receiver)
        receiver.stall(TestSetup.SCREEN_PATH)

        val job = launch(Dispatchers.IO) { ScreenTimeSyncManager(context).performSync() }
        receiver.awaitRequests(1)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        val failures = prefs.getWebhookLogs(LogType.SCREEN_TIME).filter { !it.success }
        assertEquals("no failed delivery logged for a cancellation: ${failures.map { it.errorMessage }}", 0, failures.size)
        assertEquals(0, TestSetup.streak("SCREEN_TIME"))
        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertEquals(null, prefs.getScreenTimeLastSyncTimestamp())
    }
}
