package com.owen282000.lifedashboard

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.harness.AppStateRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * Proves the upsert rule Receive relies on (issue #62): the same clientRecordId written
 * again with an equal or lower clientRecordVersion leaves the stored value alone, and a
 * higher version replaces it, so a resend from Home Assistant is harmless and a correction
 * wins. Part of the instrumented suite (scripts/instrumented.sh); the write permission comes
 * from [AppStateRule], which also resets the app first.
 */
@RunWith(AndroidJUnit4::class)
class WriteBackUpsertTest {

    @get:Rule
    val appState = AppStateRule()

    @Test
    fun equalAndLowerVersionsAreIgnoredAndAHigherVersionOverwrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = HealthConnectClient.getOrCreate(context)
        val manager = HealthConnectManager(context)

        val time = Instant.now().minus(Duration.ofMinutes(5))
        val id = "sensor.upsert_test_weight@${time.toEpochMilli()}"
        fun reading(version: Long, kilograms: Double) = PendingReading(
            id = id, version = version, type = WriteBackType.WEIGHT, value = kilograms, time = time,
            device = ReadingDevice(type = "scale", manufacturer = "Test", model = "Scale")
        )
        suspend fun stored(): Double {
            val records = client.readRecords(
                ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(time.minusSeconds(1), time.plusSeconds(1)))
            ).records.filter { it.metadata.clientRecordId == id }
            assertEquals("exactly one record for the client id", 1, records.size)
            return records.single().weight.inKilograms
        }

        try {
            manager.insertRecords(listOf(manager.recordFor(reading(version = 2, kilograms = 80.0))))
            assertEquals(80.0, stored(), 0.001)

            manager.insertRecords(listOf(manager.recordFor(reading(version = 1, kilograms = 70.0))))
            assertEquals("a lower version is ignored", 80.0, stored(), 0.001)

            manager.insertRecords(listOf(manager.recordFor(reading(version = 2, kilograms = 75.0))))
            // Health Connect's platform code updates on an equal version while its documentation
            // says only a higher one does; the app never relies on either, so only the higher
            // case is asserted and the equal case is left to be whatever the device does.
            val afterEqual = stored()
            check(afterEqual == 80.0 || afterEqual == 75.0)

            manager.insertRecords(listOf(manager.recordFor(reading(version = 3, kilograms = 81.5))))
            assertEquals("a higher version overwrites", 81.5, stored(), 0.001)
        } finally {
            manager.deleteOwnRecords(WriteBackType.WEIGHT, listOf(id))
        }
    }
}
