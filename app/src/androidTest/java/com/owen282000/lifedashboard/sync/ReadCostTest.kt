package com.owen282000.lifedashboard.sync

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.SLEEP
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.TOTAL_CALORIES
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.CountingHealthConnectClient
import com.owen282000.lifedashboard.harness.HcCall
import com.owen282000.lifedashboard.harness.HcCalls
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a sync costs in Health Connect's read quota (issue #73). Every readRecords page, every
 * aggregate call and every change feed page costs one call, and a phone in the background gets
 * about 8000 a day. A source that rewrites a day of calorie minutes on every sync, as Fitbit
 * does, keeps a type draining over several passes; those passes must not read everything
 * else again, and a quota that runs out anyway must stop the reading instead of failing
 * every remaining type one by one.
 */
@RunWith(AndroidJUnit4::class)
class ReadCostTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    private fun deleted(payload: JsonObject): List<String> =
        payload.arr("deleted_records").orEmpty().map { ((it as JsonObject)["uuid"] as JsonPrimitive).content }

    /** [count] calorie minutes ending about an hour ago, in batches with a modification time of their own. */
    private fun calorieMinutes(count: Int): List<String> {
        val start = ago(60L + count)
        return fixture.insertInBatches(List(count) { fixture.calorieMinute(start.plusSeconds(60L * it)) }, 500)
    }

    /**
     * 2,500 calorie minutes drain over several passes. The steps and the weight, done in the
     * first pass, are read once; so are the daily totals. Red on 1.21.1: every pass read every
     * type again, and the totals too.
     */
    @Test
    fun laterPassesReadOnlyTheTypesStillDraining() = runBlocking {
        TestSetup.health(receiver, setOf(TOTAL_CALORIES, STEPS, WEIGHT))
        fixture.assertNoForeignRecords(TotalCaloriesBurnedRecord::class, StepsRecord::class, WeightRecord::class)
        val calories = calorieMinutes(2500)
        val others = fixture.insert(fixture.steps(40, ago(50), ago(40)), fixture.weight(80.0, ago(30)))

        HcCalls.reset()
        TestSetup.syncManager().performSync().getOrThrow()
        val reads = HcCalls.readsByType()
        val payloads = receiver.exchanges.size

        assertTrue("the calories took more than one payload: $payloads", payloads > 1)
        assertEquals("steps read once: $reads", 1, reads["StepsRecord"])
        assertEquals("weight read once: $reads", 1, reads["WeightRecord"])
        assertTrue("at most one page per calorie pass: $reads", reads["TotalCaloriesBurnedRecord"]!! <= payloads)
        assertTrue("daily totals asked once: ${HcCalls.snapshot()}", (HcCalls.snapshot()[HcCall.AGGREGATE_GROUPED] ?: 0) <= 1)

        while (TestSetup.syncManager().performSync().getOrThrow() != HealthSyncResult.NoData) Unit
        Conservation.assertExactlyOnce((calories + others).toSet(), receiver.exchanges.map { Conservation.parse(it.text) })
    }

    /**
     * A calorie minute deleted while a new batch of minutes keeps the type draining. The
     * deletion goes out with the first payload: the record is not in what Health Connect holds
     * now, so nothing can bring it back. Red on 1.21.1: a deletion for a type still draining
     * waited for the pass that finished it, and a type that stays capped never finishes.
     */
    @Test
    fun aDeletionDoesNotWaitForItsTypeToDrain() = runBlocking {
        TestSetup.health(receiver, setOf(TOTAL_CALORIES))
        fixture.assertNoForeignRecords(TotalCaloriesBurnedRecord::class)
        val first = calorieMinutes(100)
        while (TestSetup.syncManager().performSync().getOrThrow() != HealthSyncResult.NoData) Unit

        fixture.delete(TotalCaloriesBurnedRecord::class, first.first())
        calorieMinutes(2500)
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()

        val firstPayload = Conservation.parse(receiver.since(mark).first().text)
        assertEquals(listOf(first.first()), deleted(firstPayload))
    }

    /**
     * Health Connect refuses a read once the app's quota is used up. The sync stops reading
     * there: no further type, no daily totals, and every type it did not read keeps its
     * watermark for the next sync. Red on 1.21.1: every remaining type tried and failed.
     */
    @Test
    fun aUsedUpQuotaStopsTheReading() = runBlocking {
        val types = setOf(STEPS, WEIGHT, HEART_RATE, SLEEP, TOTAL_CALORIES)
        TestSetup.health(receiver, types)
        fixture.insert(fixture.steps(40, ago(50), ago(40)), fixture.weight(80.0, ago(30)))

        val reads = AtomicInteger()
        val refused = AtomicInteger()
        val client = object : CountingHealthConnectClient(HealthConnectClient.getOrCreate(context)) {
            override suspend fun before(call: HcCall) {
                super.before(call)
                if (call == HcCall.READ_RECORDS || call == HcCall.AGGREGATE_GROUPED) {
                    if (reads.incrementAndGet() > 1) {
                        refused.incrementAndGet()
                        throw RemoteException("API call quota exceeded, availableQuota: 0.17 requested: 1")
                    }
                }
            }
        }
        HealthSyncManager(context, HealthConnectManager(context) { client }).performSync()

        assertEquals("one read refused, then no more asked", 1, refused.get())
        val prefs = context.appPreferences()
        types.filter { it != STEPS }.forEach { assertNull("$it keeps its watermark", prefs.getHealthLastSyncTimestamp(it)) }
    }
}
