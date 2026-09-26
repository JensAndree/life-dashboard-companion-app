package com.owen282000.lifedashboard

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Guards the bisection fallback for malformed source records (issue #12): a record the
 * Health Connect client refuses to read must cost at most one minimal window of data,
 * never the entire data type.
 */
class ResilientReadLogicTest {

    private data class Rec(val id: String, val time: Instant)

    private val base = Instant.parse("2026-01-01T00:00:00Z")
    private val windowEnd: Instant = base.plus(Duration.ofDays(7))

    /** Hourly records across the whole 7-day window. */
    private val hourlyRecords = (0 until 7 * 24).map { hour ->
        Rec("rec-$hour", base.plus(Duration.ofHours(hour.toLong())))
    }

    /**
     * Mimics readAllRecords(): returns instant records inside [start, end), but throws the
     * Health Connect client's error whenever the window contains a malformed record.
     */
    private fun readerWithMalformedAt(
        records: List<Rec>,
        badTimes: List<Instant>,
        onRead: () -> Unit = {}
    ): suspend (Instant, Instant) -> PagedResult<Rec> = { start, end ->
        onRead()
        if (badTimes.any { !it.isBefore(start) && it.isBefore(end) }) {
            throw IllegalArgumentException("startTime must be before endTime.")
        }
        PagedResult(records.filter { !it.time.isBefore(start) && it.time.isBefore(end) }, 1)
    }

    @Test
    fun cleanWindowIsReadInASingleCall() = runBlocking {
        var calls = 0
        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
            read = readerWithMalformedAt(hourlyRecords, badTimes = emptyList()) { calls++ })
        assertEquals(1, calls)
        assertEquals(hourlyRecords, result.records)
        assertEquals(0, result.skippedWindows)
    }

    @Test
    fun malformedRecordCostsAtMostTheMinimalWindow() = runBlocking {
        val badTime = base.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(30)).plusSeconds(17)
        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
            read = readerWithMalformedAt(hourlyRecords, badTimes = listOf(badTime)))

        assertEquals(1, result.skippedWindows)
        val lost = hourlyRecords - result.records.toSet()
        // Anything lost must sit inside the skipped minimal window around the malformed record.
        assertTrue(lost.all {
            Duration.between(it.time, badTime).abs() <= ResilientReadLogic.MIN_BISECT_WINDOW
        })
        // No good record is within 5 minutes of this badTime, so nothing may be lost at all.
        assertEquals(emptyList<Rec>(), lost)
    }

    @Test
    fun twoMalformedRegionsAreIsolatedIndependently() = runBlocking {
        val badTimes = listOf(
            base.plus(Duration.ofDays(1)).plusSeconds(42),
            base.plus(Duration.ofDays(5)).plus(Duration.ofHours(7)).plusSeconds(11)
        )
        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
            read = readerWithMalformedAt(hourlyRecords, badTimes))

        assertEquals(2, result.skippedWindows)
        val lost = hourlyRecords - result.records.toSet()
        assertTrue(lost.all { rec ->
            badTimes.any { Duration.between(rec.time, it).abs() <= ResilientReadLogic.MIN_BISECT_WINDOW }
        })
    }

    @Test
    fun intervalRecordsOverlappingSplitPointsAreDeduped() = runBlocking {
        // Interval-record semantics: a record is returned by every queried window it overlaps.
        data class IntervalRec(val id: String, val start: Instant, val end: Instant)

        val span = IntervalRec("span", base.plus(Duration.ofDays(2)), base.plus(Duration.ofDays(4)))
        val badTime = base.plus(Duration.ofDays(3))
        val read: suspend (Instant, Instant) -> PagedResult<IntervalRec> = { start, end ->
            if (!badTime.isBefore(start) && badTime.isBefore(end)) {
                throw IllegalArgumentException("startTime must be before endTime.")
            }
            val overlaps = span.start.isBefore(end) && span.end.isAfter(start)
            PagedResult(if (overlaps) listOf(span) else emptyList(), 1)
        }

        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: IntervalRec -> r.id }, read = read)
        assertEquals(1, result.records.count { it.id == "span" })
    }

    @Test
    fun unrelatedIllegalArgumentExceptionsPropagate() {
        val boom = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
                    read = { _, _ -> throw IllegalArgumentException("some other validation error") })
            }
        }
        assertEquals("some other validation error", boom.message)
    }

    // Own records (Receive, issue #62): left out of the payload, and the watermark moves past them

    private data class Stamped(val id: String, val modified: Instant, val own: Boolean)

    @Test
    fun `an own record is left out and lies below the watermark after one sync`() {
        val watermark = base
        val ownRecord = Stamped("ha-weight", base.plus(Duration.ofHours(2)), own = true)
        val fresh = listOf(ownRecord).filter { it.modified > watermark }
        val (own, foreign) = ResilientReadLogic.partitionOwn(fresh) { it.own }
        assertEquals(listOf(ownRecord), own)
        assertTrue(foreign.isEmpty())

        val next = ResilientReadLogic.watermarkAfter(foreign, own, capped = false) { it.modified }
        assertEquals(ownRecord.modified, next)
        assertTrue(
            "after the watermark is stored the own record is not new any more",
            listOf(ownRecord).none { it.modified > next!! }
        )
    }

    @Test
    fun `the watermark takes the newer of the delivered batch and the own records`() {
        val delivered = listOf(Stamped("watch", base.plus(Duration.ofHours(1)), own = false))
        val own = listOf(Stamped("ha", base.plus(Duration.ofHours(3)), own = true))
        assertEquals(base.plus(Duration.ofHours(3)), ResilientReadLogic.watermarkAfter(delivered, own, capped = false) { it.modified })
        assertEquals(base.plus(Duration.ofHours(1)), ResilientReadLogic.watermarkAfter(delivered, emptyList(), capped = false) { it.modified })
        assertEquals(null, ResilientReadLogic.watermarkAfter(emptyList<Stamped>(), emptyList(), capped = false) { it.modified })
    }

    @Test
    fun `a capped type ignores its own records so a held-back foreign record is not skipped`() {
        // Foreign records at 1h and 4h, the cap delivered only the 1h one; an own record at 3h
        // must not move the watermark past the 4h record still waiting behind the cap.
        val delivered = listOf(Stamped("watch-1", base.plus(Duration.ofHours(1)), own = false))
        val own = listOf(Stamped("ha", base.plus(Duration.ofHours(3)), own = true))
        val heldBack = Stamped("watch-2", base.plus(Duration.ofHours(4)), own = false)
        val next = ResilientReadLogic.watermarkAfter(delivered, own, capped = true) { it.modified }
        assertEquals(base.plus(Duration.ofHours(1)), next)
        assertTrue(heldBack.modified > next!!)
    }

    @Test
    fun `a record counts as written by Receive only with the app's package and a client id`() {
        val app = "com.owen282000.lifedashboard"
        assertTrue(ResilientReadLogic.isReceiveWrite(app, app, "input_number.wb_weight@1790444436456"))
        // The debug seeder writes under the app's package without a client id: read like any source.
        assertFalse(ResilientReadLogic.isReceiveWrite(app, app, null))
        assertFalse(ResilientReadLogic.isReceiveWrite(app, app, ""))
        // Another app's record is never ours, whatever its client id.
        assertFalse(ResilientReadLogic.isReceiveWrite("com.zepp.app", app, "input_number.wb_weight@1790444436456"))
    }
}
