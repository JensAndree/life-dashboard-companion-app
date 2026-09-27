package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Guards the lastSync/record-cap interaction: the cap must keep the records that come FIRST in
 * the order of modification time and id, so that resuming from the delivered batch's
 * [Watermark] never permanently skips a capped record. A takeLast-style cap (keeping the
 * newest) once caused exactly that silent data loss.
 */
class SyncCapTest {

    private data class Rec(val id: Int, val time: Instant) {
        val key: String get() = "%04d".format(id)
    }

    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun rec(minute: Int) = Rec(minute, base.plusSeconds(minute * 60L))

    private fun cap(records: List<Rec>, max: Int) =
        ResilientReadLogic.capOldestFirst(records, max, timeOf = { it.time }, idOf = { it.key })

    /** The real sync loop: read what the watermark admits, cap it, move the watermark on. */
    private fun drain(all: List<Rec>, max: Int, maxRounds: Int = 50): List<List<Rec>> {
        val batches = mutableListOf<List<Rec>>()
        var mark: Watermark? = null
        while (batches.size < maxRounds) {
            val fresh = all.filter { mark == null || mark!!.admits(it.time, it.key) }
            if (fresh.isEmpty()) break
            val batch = cap(fresh, max)
            batches += batch
            mark = ResilientReadLogic.watermarkAfter(batch, emptyList(), capped = batch.size < fresh.size, timeOf = { it.time }, idOf = { it.key })
        }
        return batches
    }

    @Test
    fun underTheLimitRecordsPassThroughUnchanged() {
        val records = listOf(rec(3), rec(1), rec(2))
        assertEquals(records, cap(records, 5))
    }

    @Test
    fun overTheLimitTheOldestRecordsAreKept() {
        val records = (1..10).map(::rec).shuffled(java.util.Random(42))
        assertEquals(listOf(1, 2, 3, 4), cap(records, 4).map { it.id })
    }

    @Test
    fun everyDroppedRecordIsNewerThanEveryKeptRecord() {
        val records = (1..100).map(::rec).shuffled(java.util.Random(7))
        val kept = cap(records, 30)
        val dropped = records - kept.toSet()
        val newestKept = kept.maxOf { it.time }
        assertEquals(30, kept.size)
        assertTrue(dropped.all { it.time > newestKept })
    }

    @Test
    fun repeatedSyncsDeliverEverythingExactlyOnce() {
        val all = (1..25).map(::rec).shuffled(java.util.Random(1))
        val batches = drain(all, max = 10)
        assertEquals(3, batches.size)
        assertEquals((1..25).toList(), batches.flatten().map { it.id }.sorted())
    }

    /**
     * Health Connect gives every record of one insert the same modification time, so a
     * backlog uploaded in one go is one big tie group. The cap cuts through it at exactly the
     * limit (F7 of P2-4); before the id was part of the order it had to take the whole group.
     */
    @Test
    fun aTieGroupIsCutAtTheCap() {
        val tied = (1..5).map { Rec(10 + it, base.plusSeconds(600)) }
        val records = listOf(rec(1), rec(2)) + tied + listOf(rec(20))
        val result = cap(records.shuffled(java.util.Random(3)), 4)
        assertEquals(listOf(1, 2, 11, 12), result.map { it.id })
    }

    @Test
    fun oneInsertOfThousandsIsDrainedInBoundedBatchesExactlyOnce() {
        val oneInsert = (0 until 2500).map { Rec(it, base) }.shuffled(java.util.Random(9))
        val batches = drain(oneInsert, max = 1000)
        assertEquals(listOf(1000, 1000, 500), batches.map { it.size })
        assertEquals((0 until 2500).toList(), batches.flatten().map { it.id }.sorted())
    }

    @Test
    fun repeatedSyncsWithTiedTimestampsStillDeliverEverythingExactlyOnce() {
        // 30 records across only 3 distinct timestamps, capped at 8 per batch.
        val all = (0 until 30).map { Rec(it, base.plusSeconds((it % 3) * 60L)) }
        val batches = drain(all, max = 8)
        assertTrue(batches.all { it.size <= 8 })
        assertEquals(all.map { it.id }.sorted(), batches.flatten().map { it.id }.sorted())
    }

    @Test
    fun aWatermarkWithoutIdStillMeansEverythingAtItsTimeWasRead() {
        // What every watermark stored before the id existed looks like: a time, no id.
        val old = Watermark(base)
        assertFalse(old.admits(base, "0000"))
        assertFalse(old.admits(base, "zzzz"))
        assertTrue(old.admits(base.plusMillis(1), "0000"))
        val cut = Watermark(base, "0500")
        assertFalse(cut.admits(base, "0500"))
        assertTrue(cut.admits(base, "0501"))
    }

    @Test
    fun sampleRecordCapKeepsWholeRecordsAndStopsInsideATieGroup() {
        data class HrRec(val id: String, val samples: Int, val modified: Instant)

        val records = listOf(
            HrRec("1", samples = 400, modified = base.plusSeconds(60)),
            HrRec("2", samples = 400, modified = base.plusSeconds(120)),
            HrRec("3", samples = 400, modified = base.plusSeconds(180)),
            HrRec("4", samples = 400, modified = base.plusSeconds(180)),
            HrRec("5", samples = 400, modified = base.plusSeconds(240))
        )
        val included = ResilientReadLogic.capRecordsBySamples(
            records, maxSamples = 1000, samplesOf = { it.samples }, timeOf = { it.modified }, idOf = { it.id }
        )
        // Whole records until the budget is reached: the third takes it past 1000, the fourth,
        // although it shares the third's time, waits for the next sync.
        assertEquals(listOf("1", "2", "3"), included.map { it.id })
    }

    @Test
    fun repeatedSampleRecordSyncsDeliverEverythingExactlyOnce() {
        data class HrRec(val id: String, val samples: Int, val modified: Instant)

        // 50 records of 100 samples from one insert, the shape of the F7 finding.
        val all = (0 until 50).map { HrRec("%02d".format(it), samples = 100, modified = base) }
        val delivered = mutableListOf<List<HrRec>>()
        var mark: Watermark? = null
        while (delivered.size < 50) {
            val fresh = all.filter { mark == null || mark!!.admits(it.modified, it.id) }
            if (fresh.isEmpty()) break
            val batch = ResilientReadLogic.capRecordsBySamples(
                fresh, maxSamples = 1000, samplesOf = { it.samples }, timeOf = { it.modified }, idOf = { it.id }
            )
            delivered += batch
            mark = ResilientReadLogic.watermarkAfter(batch, emptyList(), capped = batch.size < fresh.size, timeOf = { it.modified }, idOf = { it.id })
        }
        assertEquals(listOf(10, 10, 10, 10, 10), delivered.map { it.size })
        assertEquals(all.map { it.id }, delivered.flatten().map { it.id }.sorted())
    }

    /**
     * Watch apps (Zepp, Garmin) upload data hours later with the ORIGINAL record timestamps.
     * The sync watermark therefore runs on modification time, not record time: a backfilled
     * record has an old record time but a recent modification time and must still sync.
     */
    @Test
    fun backfilledRecordsWithOldTimestampsAreStillDelivered() {
        data class R(val id: String, val time: Instant, val modified: Instant)

        fun at(hour: Int, minute: Int) = base.plusSeconds((hour * 3600 + minute * 60).toLong())
        var watermark: Watermark? = null
        fun sync(all: List<R>): List<R> {
            val fresh = all.filter { watermark == null || watermark!!.admits(it.modified, it.id) }
            val batch = ResilientReadLogic.capOldestFirst(fresh, maxLimit = 100, timeOf = { it.modified }, idOf = { it.id })
            ResilientReadLogic.watermarkAfter(batch, emptyList(), capped = batch.size < fresh.size, timeOf = { it.modified }, idOf = { it.id })
                ?.let { watermark = it }
            return batch
        }

        // Round 1: live data measured and uploaded at 10:00.
        val live = R("1", time = at(10, 0), modified = at(10, 0))
        assertEquals(listOf("1"), sync(listOf(live)).map { it.id })

        // Round 2: the watch backfills an 08:00 record at 12:05. A record-time watermark
        // (10:00) would skip it forever; the modification-time watermark picks it up.
        val backfilled = R("2", time = at(8, 0), modified = at(12, 5))
        assertEquals(listOf("2"), sync(listOf(live, backfilled)).map { it.id })

        // Round 3: nothing new, nothing re-sent.
        assertEquals(emptyList<String>(), sync(listOf(live, backfilled)).map { it.id })
    }
}
