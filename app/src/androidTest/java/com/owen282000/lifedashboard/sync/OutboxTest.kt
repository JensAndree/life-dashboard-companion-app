package com.owen282000.lifedashboard.sync

import android.content.Context
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Hmac
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The outbox: a payload the receiver could not take is kept on disk and delivered first next time. */
@RunWith(AndroidJUnit4::class)
class OutboxTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    /**
     * T11. Three 503s: the app tries three times with its 1 s and 2 s backoff, queues the payload
     * byte for byte, logs the failure and still moves the watermarks, because the outbox now
     * owns delivery. The next sync delivers that same payload, same sequence and signature,
     * and nothing twice: every seeded record reaches the receiver in a 2xx exactly once.
     *
     * Whether the drain resets the failure streak is left to F5 (P2-4, fase 3), which changes it.
     */
    @Test
    fun transient503QueuesThenDrains() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS, HEART_RATE, WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, HeartRateRecord::class, WeightRecord::class)
        val beats = listOf(ago(25) to 71L, ago(24) to 74L, ago(23) to 78L)
        val (stepsId, heartRateId, weightId) = fixture.insert(
            fixture.steps(1234, ago(40), ago(30)),
            fixture.heartRate(beats),
            fixture.weight(78.4, ago(20))
        )
        val seeded = setOf(stepsId, weightId) + beats.map { (time, _) -> "$heartRateId#${time.toEpochMilli()}" }
        receiver.respond(TestSetup.HEALTH_PATH, 503, 503, 503, 200)

        val first = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Queued(5), first)
        val attempts = receiver.exchanges
        assertEquals("three attempts, then give up", 3, attempts.size)
        assertTrue(attempts.all { it.responseCode == 503 })
        attempts.forEach { assertArrayEquals("every retry is the same request", attempts[0].body, it.body) }
        val backoff = attempts.last().receivedAtMs - attempts.first().receivedAtMs
        assertTrue("backoff of 1 s and 2 s, took $backoff ms", backoff in 2_800..8_000)

        val queued = PendingSyncStore.forContext(context).peekAll().single()
        assertEquals("the outbox holds the payload byte for byte", attempts.last().text, queued.payload)
        val log = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertTrue(log.errorMessage.orEmpty(), log.errorMessage.orEmpty().startsWith("Failed after 3 attempts (transient errors): HTTP 503"))
        assertEquals(1, streak(context))
        assertWatermarksAtNewestRecord()

        val mark = receiver.exchanges.size
        val second = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.NoData, second)
        val drained = receiver.since(mark).single()
        assertEquals(200, drained.responseCode)
        assertArrayEquals("the drain sends the queued bytes", attempts.last().body, drained.body)
        assertEquals("and the original signature", attempts.last().header("X-Signature"), drained.header("X-Signature"))
        assertEquals(Hmac.requestSignature(TestSetup.HEALTH_SECRET, drained.body), drained.header("X-Signature"))
        assertEquals(0, PendingSyncStore.forContext(context).size())

        val delivered = receiver.exchanges.filter { it.responseCode in 200..299 }.map { Conservation.parse(it.text) }
        Conservation.assertExactlyOnce(seeded, delivered)
    }

    private fun streak(context: Context): Int =
        context.getSharedPreferences("life_dashboard_prefs", Context.MODE_PRIVATE).getInt("sync_failure_streak_HEALTH_CONNECT", 0)

    /** The failed sync moved each type's watermark to its newest record's modification time. */
    private fun assertWatermarksAtNewestRecord() {
        val newest: Map<HealthDataType, Long> = mapOf(
            STEPS to fixture.read(StepsRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() },
            HEART_RATE to fixture.read(HeartRateRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() },
            WEIGHT to fixture.read(WeightRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() }
        )
        newest.forEach { (type, millis) ->
            assertEquals("watermark of $type", millis, context.appPreferences().getHealthLastSyncTimestamp(type))
        }
    }
}
