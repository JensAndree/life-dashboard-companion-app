package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Await
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.ScreenTimeUse
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A sync that is cancelled (a stopped worker, a timeout, a screen going away) must unwind
 * as a cancellation: no failure logged, no streak, no outbox item, nothing moved. The rule
 * that 1.18.0 taught, tested without WorkManager so only the sync code is in the way.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
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

    /**
     * F4. Heart rate bucketed per minute. A sync sends the closed minute and carries the
     * current one, still open, and is interrupted while the receiver has not answered. Once
     * that minute is over, the next sync sends it with each of its samples counted once.
     *
     * Red on main: the carry is stored before the POST and the watermark only after it, so
     * the interrupted sync leaves the open minute's samples in the carry while the next read
     * returns them again, and the window goes out with its samples counted twice.
     */
    @Ignore("F4: fixed in phase 3")
    @Test
    fun interruptedSyncDoesNotCountBucketedSamplesTwice() = runBlocking {
        TestSetup.health(receiver, setOf(HEART_RATE))
        fixture.assertNoForeignRecords(HeartRateRecord::class)
        prefs.setSeriesResolutions(mapOf(HEART_RATE to SeriesResolution.ONE_MINUTE))
        // Far enough into a minute that it holds two past samples, early enough to sync in it.
        Await.until("the clock to be 15 to 30 s into a minute", 60_000, 200) { LocalTime.now().second in 15..30 }
        val minute = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        fixture.insert(fixture.heartRate(listOf(minute.minusSeconds(50) to 60L, minute.minusSeconds(30) to 62L)))
        val openMinute = listOf(minute.plusSeconds(2) to 70L, minute.plusSeconds(6) to 72L, minute.plusSeconds(10) to 74L)
        fixture.insert(fixture.heartRate(openMinute))
        receiver.stall(TestSetup.HEALTH_PATH)

        val job = launch(Dispatchers.IO) { TestSetup.syncManager().performSync() }
        receiver.awaitRequests(1)
        job.cancel()
        job.join()
        assertEquals("the open minute was carried", openMinute.size, prefs.getBucketCarry()[HEART_RATE].orEmpty().size)

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        Await.until("the carried minute to be over", 70_000, 500) { Instant.now() > minute.plusSeconds(61) }
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()

        val buckets = receiver.since(mark).flatMap { Conservation.parse(it.text).arr("heart_rate").orEmpty() }.map { it as JsonObject }
        val carried = buckets.single { it.str("bucket_start") == minute.toString() }
        assertEquals("each sample of the minute once", openMinute.size.toString(), carried.num("sample_count"))
    }
}
