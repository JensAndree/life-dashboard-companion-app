package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.TestSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What `daily_totals` does with one app writing the same stretch of time twice, as
 * Gadgetbridge does with the last minute of an export (docs/DATA_SOURCES.md) and UREVO with a
 * whole session. The totals are Health Connect's own aggregate, so this pins down behaviour of
 * the platform that the docs promise receivers: an overlap within one app counts once, and for
 * the same interval the record written last counts, while records that do not overlap all count,
 * also Gadgetbridge's copy of a minute that it put one minute late. Each case gets a day of its own.
 */
@RunWith(AndroidJUnit4::class)
class DailyTotalsOverlapTest {

    @get:Rule
    val state = AppStateRule()

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val zone = ZoneId.systemDefault()

    private fun noon(daysAgo: Long, plusSeconds: Long = 0): Instant =
        LocalDate.now().minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().plusSeconds(plusSeconds)

    private fun minute(count: Long, daysAgo: Long, fromSecond: Long = 0) {
        fixture.insert(fixture.steps(count, noon(daysAgo, fromSecond), noon(daysAgo, fromSecond + 60)))
        Thread.sleep(50) // a modification time of its own
    }

    private fun stepsOn(daysAgo: Long): Long? = runBlocking {
        HealthConnectManager(context).readDailyTotalsBetween(
            LocalDate.now().minusDays(daysAgo).atStartOfDay(), LocalDate.now().minusDays(daysAgo - 1).atStartOfDay(), setOf(STEPS)
        ).singleOrNull()?.steps
    }

    @Test
    fun theSameMinuteWrittenTwiceCountsOnce() {
        fixture.assertNoForeignRecords(StepsRecord::class)
        minute(30, daysAgo = 3)
        minute(30, daysAgo = 3)
        assertEquals(30L, stepsOn(3))
    }

    @Test
    fun theRecordWrittenLastCounts() {
        fixture.assertNoForeignRecords(StepsRecord::class)
        // A partial minute, then the complete one: the complete one counts.
        minute(20, daysAgo = 4)
        minute(30, daysAgo = 4)
        assertEquals(30L, stepsOn(4))
        // The other way round the later, smaller one counts: it is the latest write, not the largest value.
        minute(30, daysAgo = 5)
        minute(20, daysAgo = 5)
        assertEquals(20L, stepsOn(5))
    }

    @Test
    fun aPartialOverlapCountsTheOverlapOnce() {
        fixture.assertNoForeignRecords(StepsRecord::class)
        // Two minutes of 30 overlapping by half: the first whole, and half of the second.
        minute(30, daysAgo = 6)
        minute(30, daysAgo = 6, fromSecond = 30)
        assertEquals(45L, stepsOn(6))
    }

    @Test
    fun aCopyInTheNextMinuteCountsToo() {
        fixture.assertNoForeignRecords(StepsRecord::class)
        // Gadgetbridge with a Huawei watch: a minute, and the same count again one minute later.
        minute(30, daysAgo = 7)
        minute(30, daysAgo = 7, fromSecond = 60)
        assertEquals(60L, stepsOn(7))
    }
}
