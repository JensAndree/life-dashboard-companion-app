package com.owen282000.lifedashboard.fixture

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Writes, deletes and writes again the way Fitbit revises a night or a day of calories: under
 * the same client record ids, so Health Connect gives the records their old ids back (issues
 * #71 and #72). Started by the app's ReinsertedRecordTest through the shell, with arguments:
 *
 * - `kind`: `sleep` (one night with stages) or `calories` (`count` minute records)
 * - `at`: the start, in epoch seconds
 * - `ops`: a comma list of `insert`, `delete` and `reinsert` (the first `keep` records only),
 *   run in that order
 */
@RunWith(AndroidJUnit4::class)
class ReviseForSuite {

    @Test
    fun revise() = runBlocking {
        SelfGrant.ensure()
        val args = InstrumentationRegistry.getArguments()
        val client = HealthConnectClient.getOrCreate(InstrumentationRegistry.getInstrumentation().targetContext)
        val at = Instant.ofEpochSecond(args.getString("at")!!.toLong())
        val count = args.getString("count")?.toInt() ?: 1
        val keep = args.getString("keep")?.toInt() ?: count
        val offset = ZoneId.systemDefault().rules.getOffset(at)
        val watch = Device(manufacturer = "LdFixture", model = "Watch", type = Device.TYPE_WATCH)

        val records: List<Record> = when (args.getString("kind")) {
            "sleep" -> {
                val end = at.plus(Duration.ofHours(6))
                listOf(
                    SleepSessionRecord(
                        startTime = at, startZoneOffset = offset, endTime = end, endZoneOffset = offset,
                        metadata = Metadata.autoRecorded(watch, "ldsuite-night", 1),
                        stages = listOf(
                            SleepSessionRecord.Stage(at, at.plus(Duration.ofHours(3)), SleepSessionRecord.STAGE_TYPE_LIGHT),
                            SleepSessionRecord.Stage(at.plus(Duration.ofHours(3)), end, SleepSessionRecord.STAGE_TYPE_DEEP)
                        )
                    )
                )
            }
            else -> List(count) { i ->
                val start = at.plusSeconds(60L * i)
                TotalCaloriesBurnedRecord(
                    start, offset, start.plusSeconds(60), offset, Energy.kilocalories(1.0 + (i % 5) / 10.0),
                    Metadata.autoRecorded(watch, "ldsuite-cal-$i", 1)
                )
            }
        }
        val type = records.first()::class
        for (op in args.getString("ops")!!.split(",")) {
            when (op) {
                "insert" -> records.chunked(100).forEach { client.insertRecords(it) }
                "reinsert" -> records.take(keep).chunked(100).forEach { client.insertRecords(it) }
                "delete" -> client.deleteRecords(type, emptyList(), records.map { it.metadata.clientRecordId!! })
                else -> error("unknown op $op")
            }
        }
        println("Revised: ${args.getString("ops")}")
    }
}
