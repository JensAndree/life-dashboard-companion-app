package com.owen282000.lifedashboard.sync

import android.content.Context
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.FakeIntegration
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.obj
import com.owen282000.lifedashboard.harness.str
import com.owen282000.lifedashboard.harness.strings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.ZoneOffset

/**
 * Receive (issue #62) end to end: the signed answer of the integration, written into Health
 * Connect, acknowledged on the next request, and never sent back out. The integration is
 * [FakeIntegration], which checks and signs with code of its own.
 */
@RunWith(AndroidJUnit4::class)
class ReceiveTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val integration = FakeIntegration(TestSetup.HEALTH_SECRET)

    /**
     * T32. Four syncs. 1: a heartbeat asks for weight and blood pressure, and the two offered
     * readings land in Health Connect exactly as sent. 2: the next request acknowledges both
     * and the integration drops them. 3: with weight read as well, a weight measured on the
     * phone goes out and the received one does not (the echo filter, e1f6ebe). 4: deleting
     * both from Health Connect reports only the phone's own weight as deleted, never the one
     * Home Assistant sent.
     */
    @Test
    fun roundTripWritesAcksAndDoesNotEcho() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS), receive = setOf(WriteBackType.WEIGHT, WriteBackType.BLOOD_PRESSURE))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class, BloodPressureRecord::class)
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        val weighed = ago(10)
        val measured = ago(8)
        val weightId = "sensor.scale_weight@${weighed.toEpochMilli()}"
        val pressureId = "sensor.omron_systolic@${measured.toEpochMilli()}"
        integration.offer(
            FakeIntegration.weight(weightId, 81.35, weighed),
            FakeIntegration.bloodPressure(pressureId, 128.0, 82.0, measured)
        )
        val sync = TestSetup.syncManager()

        // Sync 1: nothing to read, so a heartbeat carries the request for readings.
        val first = sync.performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(emptyMap(), written = 2), first)
        val heartbeat = integration.seen.single()
        assertTrue("the request is signed with the section's secret", heartbeat.signatureValid)
        assertEquals(setOf("timestamp", "app_version", "source", "writeback"), heartbeat.body.keys)
        val block = heartbeat.writeback!!
        assertEquals("1", block.num("protocol"))
        assertEquals(listOf("weight", "blood_pressure"), block["types"].strings())
        assertEquals("false", block.num("history"))
        assertEquals(emptyList<String>(), block["ack"].strings())
        assertEquals(0, block.arr("failed")?.size)

        val weight = fixture.read(WeightRecord::class).single()
        assertEquals(weightId, weight.metadata.clientRecordId)
        assertEquals(1L, weight.metadata.clientRecordVersion)
        assertEquals(weighed, weight.time)
        assertEquals(ZoneOffset.of("+02:00"), weight.zoneOffset)
        assertEquals(81.35, weight.weight.inKilograms, 0.001)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, weight.metadata.recordingMethod)
        assertEquals(Device.TYPE_SCALE, weight.metadata.device?.type)
        assertEquals("Xiaomi", weight.metadata.device?.manufacturer)
        val pressure = fixture.read(BloodPressureRecord::class).single()
        assertEquals(pressureId, pressure.metadata.clientRecordId)
        assertEquals(measured, pressure.time)
        assertEquals(128.0, pressure.systolic.inMillimetersOfMercury, 0.001)
        assertEquals(82.0, pressure.diastolic.inMillimetersOfMercury, 0.001)
        assertEquals(BloodPressureRecord.BODY_POSITION_SITTING_DOWN, pressure.bodyPosition)
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM, pressure.measurementLocation)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, pressure.metadata.recordingMethod)
        assertEquals("Omron", pressure.metadata.device?.manufacturer)

        val prefs = context.appPreferences()
        assertEquals(
            "the ledger names the records Health Connect holds",
            mapOf(weightId to weight.metadata.id, pressureId to pressure.metadata.id),
            prefs.getWriteBackLedger().entries.associate { it.id to it.recordId }
        )
        assertEquals(2, SyncStatusStore.writtenToday(context))
        val inRows = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { it.direction == LogDirection.IN.name }
        assertTrue(inRows.single().success)
        assertEquals(0, receiveStreak(context))

        // Sync 2: the acknowledgements ride on the next request; the integration drops both.
        sync.performSync().getOrThrow()

        val second = integration.seen[1]
        assertTrue(second.signatureValid)
        assertEquals(setOf(weightId, pressureId), second.ack().toSet())
        assertEquals(emptyList<String>(), integration.pendingIds())
        assertTrue("nothing left to acknowledge", prefs.getWriteBackReport().isEmpty)
        assertEquals("nothing offered, no new row", 1, prefs.getWebhookLogs(LogType.HEALTH_CONNECT).count { it.direction == LogDirection.IN.name })

        // Sync 3: weight is read too; the phone's own weight goes out, the received one does not.
        prefs.setHealthEnabledDataTypes(setOf(STEPS, WEIGHT))
        val (phoneWeightId) = fixture.insert(fixture.weight(80.9, ago(5)))
        val mark = receiver.exchanges.size
        sync.performSync().getOrThrow()

        val third = receiver.since(mark).single()
        assertEquals(emptyList<String>(), Schema.errors(third.text, "sync3"))
        val body = Conservation.parse(third.text)
        Conservation.assertExactlyOnce(setOf(phoneWeightId), listOf(body))
        assertEquals("1", body.obj("_diagnostics")?.obj("weight")?.num("own_records_skipped"))
        assertEquals("health_connect", body.str("source"))

        // Sync 4: both weights deleted; only the phone's own is reported as deleted.
        fixture.delete(WeightRecord::class, weight.metadata.id, phoneWeightId)
        val mark4 = receiver.exchanges.size
        sync.performSync().getOrThrow()

        val deletion = Conservation.parse(receiver.since(mark4).single().text)
        val deleted = deletion.arr("deleted_records").orEmpty().map { (it as kotlinx.serialization.json.JsonObject).str("uuid") }
        assertEquals(listOf(phoneWeightId), deleted)
        assertFalse("Home Assistant never hears back about its own weight", receiver.since(mark4).any { weight.metadata.id in it.text })
        assertNull(deletion["deletions_unavailable"])
    }

    private fun receiveStreak(context: Context): Int =
        context.getSharedPreferences("life_dashboard_prefs", Context.MODE_PRIVATE).getInt("sync_failure_streak_RECEIVE", 0)
}
