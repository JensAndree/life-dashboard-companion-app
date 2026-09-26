package com.owen282000.lifedashboard.fixture

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Deletes everything the fixture ever wrote. Health Connect keeps an uninstalled app's data,
 * so run this before removing the fixture from a device:
 *
 *   adb shell am instrument -w --no-hidden-api-checks -e class com.owen282000.lifedashboard.fixture.ClearFixtureData \
 *     com.owen282000.lifedashboard.fixture.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ClearFixtureData {

    @Test
    fun deleteEverythingTheFixtureWrote() = runBlocking {
        SelfGrant.ensure()
        val client = HealthConnectClient.getOrCreate(InstrumentationRegistry.getInstrumentation().targetContext)
        val everything = TimeRangeFilter.after(Instant.EPOCH)
        listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, WeightRecord::class, SleepSessionRecord::class,
            MindfulnessSessionRecord::class
        ).forEach { client.deleteRecords(it, everything) }
        println("Deleted every record this fixture wrote")
    }
}
