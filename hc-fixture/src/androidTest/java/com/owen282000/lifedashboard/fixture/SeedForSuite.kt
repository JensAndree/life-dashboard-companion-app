package com.owen282000.lifedashboard.fixture

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The small, exact set of foreign records the app's ForeignSourceTest syncs: three weights and
 * one steps record from this app, each with a client record id as a real watch or scale app
 * sets one. Started by that test through the shell; ClearFixtureData removes it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SeedForSuite {

    @Test
    fun seedForeignRecords() = runBlocking {
        SelfGrant.ensure()
        val client = HealthConnectClient.getOrCreate(InstrumentationRegistry.getInstrumentation().targetContext)
        val scale = Device(manufacturer = "LdFixture", model = "Scale", type = Device.TYPE_SCALE)
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        fun ago(minutes: Long) = now.minus(Duration.ofMinutes(minutes))
        val offset = ZoneId.systemDefault().rules.getOffset(now)
        client.insertRecords(
            List(3) { i ->
                WeightRecord(ago(40L - i * 5), offset, Mass.kilograms(77.0 + i / 10.0), Metadata.autoRecorded(scale, "ldsuite-foreign-weight-$i", 1))
            } + StepsRecord(ago(30), offset, ago(20), offset, 321, Metadata.autoRecorded(scale, "ldsuite-foreign-steps", 1))
        )
        println("Seeded the foreign records for the suite")
    }
}
