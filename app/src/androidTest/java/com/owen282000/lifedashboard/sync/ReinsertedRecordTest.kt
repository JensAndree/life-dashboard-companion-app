package com.owen282000.lifedashboard.sync

import android.os.ParcelFileDescriptor
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.SLEEP
import com.owen282000.lifedashboard.HealthDataType.TOTAL_CALORIES
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.CountingHealthConnectClient
import com.owen282000.lifedashboard.harness.HcCall
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.reflect.KClass

/**
 * A source that revises records by deleting them and writing them again under the same client
 * record id, as Fitbit does with a night of sleep or a day of calories (issues #71 and #72).
 * Health Connect then gives the new record the old id, and its changes feed reports that id as
 * deleted and as written. A receiver that applies deleted_records must end up with the records
 * Health Connect holds, so the app may only name a deletion for a record that is really gone.
 *
 * The :hc-fixture app plays the source (ReviseForSuite): the app itself cannot, because what it
 * writes with a client record id is its own Receive write and never goes out.
 */
@RunWith(AndroidJUnit4::class)
class ReinsertedRecordTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    private fun revise(vararg args: Pair<String, String>) {
        val extras = args.joinToString("") { (key, value) -> " -e $key $value" }
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am instrument -w --no-hidden-api-checks -e class $FIXTURE.ReviseForSuite$extras $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner"
        ).let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }
        assertTrue("the fixture ran: $output", "OK (1 test)" in output)
    }

    private fun clearFixture() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am instrument -w --no-hidden-api-checks -e class $FIXTURE.ClearFixtureData $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner"
        ).let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }
    }

    private fun <T : Record> foreignIds(type: KClass<T>): Set<String> =
        fixture.read(type).filter { it.metadata.dataOrigin.packageName == FIXTURE }.map { it.metadata.id }.toSet()

    private fun deleted(payload: JsonObject): List<String> =
        payload.arr("deleted_records").orEmpty().map { ((it as JsonObject)["uuid"] as JsonPrimitive).content }

    /** Syncs until the app has nothing new, and returns the payloads that went out. */
    private fun syncAll(): List<JsonObject> = runBlocking {
        val mark = receiver.exchanges.size
        for (round in 1..6) {
            if (TestSetup.syncManager().performSync().getOrThrow() == HealthSyncResult.NoData) break
        }
        receiver.since(mark).map { Conservation.parse(it.text) }
    }

    /**
     * What a receiver that applies every payload literally holds afterwards: payloads in the
     * order of their sequence, within a payload the records first and then deleted_records,
     * the strictest reading and the one the reporter's receiver follows.
     */
    private fun replay(payloads: List<JsonObject>, key: String): Set<String> {
        val held = mutableSetOf<String>()
        payloads.sortedBy { it.num("sequence")?.toLong() ?: 0 }.forEach { payload ->
            (payload[key] as? JsonArray).orEmpty().forEach { held += ((it as JsonObject)["uuid"] as JsonPrimitive).content }
            held -= deleted(payload).toSet()
        }
        return held
    }

    /** Whether Health Connect reports [id] as deleted since [token], so a green test cannot mean the platform stayed silent. */
    private fun feedNamesDeletion(token: String, id: String): Boolean = runBlocking {
        fixture.client.getChanges(token).changes.any { it is DeletionChange && it.recordId == id }
    }

    private fun token(type: KClass<out Record>): String = runBlocking {
        fixture.client.getChangesToken(ChangesTokenRequest(setOf(type)))
    }

    /** #71. A night deleted and written again under its old id arrives again and is never named deleted. */
    @Test
    fun reinsertedNightIsNotWithdrawn() {
        TestSetup.health(receiver, setOf(SLEEP))
        fixture.assertNoForeignRecords(SleepSessionRecord::class)
        val at = "at" to "${ago(9 * 60).epochSecond}"
        try {
            revise("kind" to "sleep", at, "ops" to "insert")
            val night = foreignIds(SleepSessionRecord::class).single()
            assertEquals(setOf(night), replay(syncAll(), "sleep"))

            val witness = token(SleepSessionRecord::class)
            revise("kind" to "sleep", at, "ops" to "delete,insert")
            assertEquals("the same id again", setOf(night), foreignIds(SleepSessionRecord::class))
            assumeTrue("Health Connect reports the deletion of a record written again", feedNamesDeletion(witness, night))

            val second = syncAll()
            assertTrue("never named deleted: ${second.flatMap(::deleted)}", second.flatMap(::deleted).none { it == night })
            assertEquals("a receiver still holds the night", setOf(night), replay(receiver.exchanges.map { Conservation.parse(it.text) }, "sleep"))
        } finally {
            clearFixture()
        }
    }

    /** A night that is really gone is still named deleted, also when it was written again and deleted once more. */
    @Test
    fun aGenuineDeletionStillGoesOut() {
        TestSetup.health(receiver, setOf(SLEEP))
        fixture.assertNoForeignRecords(SleepSessionRecord::class)
        val at = "at" to "${ago(9 * 60).epochSecond}"
        try {
            revise("kind" to "sleep", at, "ops" to "insert")
            val night = foreignIds(SleepSessionRecord::class).single()
            syncAll()

            revise("kind" to "sleep", at, "ops" to "delete,insert,delete")
            assertTrue(foreignIds(SleepSessionRecord::class).isEmpty())
            val payloads = syncAll()
            assertEquals(listOf(night), payloads.flatMap(::deleted))
            assertTrue(replay(receiver.exchanges.map { Conservation.parse(it.text) }, "sleep").isEmpty())
        } finally {
            clearFixture()
        }
    }

    /**
     * #72. A day of 400 calorie minutes, then the source deletes all of them and writes 240
     * back under their old ids. The cap sends 200 per pass, so the records and the deletions
     * cross passes. A receiver must end with exactly the 240, and the 160 that are gone must
     * be named deleted.
     */
    @Test
    fun rewrittenCalorieDayEndsAsHealthConnectHasIt() {
        TestSetup.health(receiver, setOf(TOTAL_CALORIES))
        fixture.assertNoForeignRecords(TotalCaloriesBurnedRecord::class)
        val base = arrayOf("kind" to "calories", "at" to "${ago(8 * 60).epochSecond}", "count" to "400")
        try {
            revise(*base, "ops" to "insert")
            val all = foreignIds(TotalCaloriesBurnedRecord::class)
            assertEquals(400, all.size)
            assertEquals(all, replay(syncAll(), "total_calories"))

            val witness = token(TotalCaloriesBurnedRecord::class)
            revise(*base, "keep" to "240", "ops" to "delete,reinsert")
            val kept = foreignIds(TotalCaloriesBurnedRecord::class)
            assertEquals(240, kept.size)
            assertTrue("the kept minutes have their old ids", all.containsAll(kept))
            assumeTrue("Health Connect reports the deletions", feedNamesDeletion(witness, kept.first()))

            val second = syncAll()
            assertEquals("exactly the minutes that are gone are named", all - kept, second.flatMap(::deleted).toSet())
            assertEquals(kept, replay(receiver.exchanges.map { Conservation.parse(it.text) }, "total_calories"))
        } finally {
            clearFixture()
        }
    }

    /**
     * A deletion stored by a sync that was stopped, and the night written again before the next
     * one. The next sync's feed only shows the write, so the stored deletion must be dropped
     * there; it would otherwise ride along with the night it deletes.
     */
    @Test
    fun aStoredDeletionOfANightWrittenAgainIsDropped() = runBlocking {
        TestSetup.health(receiver, setOf(SLEEP, WEIGHT))
        fixture.assertNoForeignRecords(SleepSessionRecord::class)
        val at = "at" to "${ago(9 * 60).epochSecond}"
        try {
            revise("kind" to "sleep", at, "ops" to "insert")
            val night = foreignIds(SleepSessionRecord::class).single()
            syncAll()
            val prefs = context.appPreferences()
            val sleepToken = prefs.getHealthChangesToken(SLEEP)

            // Sleep is read first; the changes read after it hangs until the sync is stopped.
            revise("kind" to "sleep", at, "ops" to "delete")
            val hung = CompletableDeferred<Unit>()
            val client = object : CountingHealthConnectClient(HealthConnectClient.getOrCreate(context)) {
                override suspend fun before(call: HcCall) {
                    super.before(call)
                    if (call == HcCall.GET_CHANGES && prefs.getHealthChangesToken(SLEEP) != sleepToken) {
                        hung.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val sync = launch(Dispatchers.IO) { HealthSyncManager(context, HealthConnectManager(context) { client }).performSync() }
            withTimeout(20_000) { hung.await() }
            sync.cancelAndJoin()
            assertEquals("the deletion is stored", listOf(night), prefs.getPendingDeletions().deleted.map { it.uuid })

            revise("kind" to "sleep", at, "ops" to "insert")
            val next = syncAll()
            assertTrue("not named deleted: ${next.flatMap(::deleted)}", next.flatMap(::deleted).none { it == night })
            assertTrue(prefs.getPendingDeletions().deleted.isEmpty())
            assertEquals(setOf(night), replay(receiver.exchanges.map { Conservation.parse(it.text) }, "sleep"))
        } finally {
            clearFixture()
        }
    }

    private companion object {
        const val FIXTURE = "com.owen282000.lifedashboard.fixture"
    }
}
