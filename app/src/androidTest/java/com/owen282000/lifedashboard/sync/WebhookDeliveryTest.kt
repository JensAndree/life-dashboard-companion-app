package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Hmac
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.obj
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/** The ordinary delivery: what one sync reads from Health Connect and how it goes over the wire. */
@RunWith(AndroidJUnit4::class)
class WebhookDeliveryTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    /**
     * T05. One sync of three types: every seeded record arrives exactly once, in one signed
     * POST that the documented schema accepts, with the headers and bookkeeping the app
     * promises. The signature is recomputed with the suite's own HMAC code.
     */
    @Test
    fun healthSyncSendsSignedValidPayload() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS, HEART_RATE, WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, HeartRateRecord::class, WeightRecord::class)
        val beats = listOf(ago(25) to 71L, ago(24) to 74L, ago(23) to 78L)
        val (stepsId, heartRateId, weightId) = fixture.insert(
            fixture.steps(1234, ago(40), ago(30)),
            fixture.heartRate(beats),
            fixture.weight(78.4, ago(20))
        )
        val heartRateUuids = beats.map { (time, _) -> "$heartRateId#${time.toEpochMilli()}" }
        val seeded = setOf(stepsId, weightId) + heartRateUuids

        val started = Instant.now()
        val result = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(mapOf(STEPS to 1, HEART_RATE to 3, WEIGHT to 1)), result)
        val post = receiver.exchanges.single()
        assertEquals("POST", post.request.method)
        assertEquals(TestSetup.HEALTH_PATH, post.path)
        assertEquals("application/json; charset=utf-8", post.header("Content-Type"))
        assertEquals("ci-key-health", post.header("X-Api-Key"))
        assertEquals("session=ci", post.header("Cookie"))
        assertTrue("User-Agent is OkHttp's own", post.header("User-Agent").orEmpty().startsWith("okhttp/"))
        assertEquals(Hmac.requestSignature(TestSetup.HEALTH_SECRET, post.body), post.header("X-Signature"))
        assertEquals(emptyList<String>(), Schema.errors(post.text))

        val body = Conservation.parse(post.text)
        Conservation.assertExactlyOnce(seeded, listOf(body))
        assertEquals("health_connect", body.str("source"))
        assertEquals(TestSetup.versionName(), body.str("app_version"))
        val timestamp = Instant.parse(body.str("timestamp"))
        assertTrue("timestamp $timestamp is the time of the sync", Duration.between(started, timestamp).abs() < Duration.ofMinutes(1))
        assertEquals("1", body.num("sequence"))
        Conservation.records(body).forEach { (key, uuid, source) ->
            assertTrue("$key record has a uuid", !uuid.isNullOrEmpty())
            assertEquals("$key record names its source", context.packageName, source)
        }
        assertEquals(heartRateUuids.toSet(), Conservation.records(body).filter { it.first == "heart_rate" }.map { it.second }.toSet())
        val steps = body.obj("_diagnostics")?.obj("steps")
        assertEquals("true", steps?.num("permission_granted"))
        assertEquals("0", steps?.num("own_records_skipped"))
        assertNull("no writeback block while Receive is off", body["writeback"])

        val log = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertTrue(log.success)
        assertEquals(200, log.statusCode)
        assertEquals(LogDirection.OUT.name, log.direction)
        assertEquals(5, SyncStatusStore.read(context, LogType.HEALTH_CONNECT).recordsToday)
    }
}
