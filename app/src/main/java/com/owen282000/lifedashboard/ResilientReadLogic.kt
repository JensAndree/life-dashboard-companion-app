package com.owen282000.lifedashboard

import java.time.Duration
import java.time.Instant

/** Result of reading all pages of one record type within a time window. */
data class PagedResult<T>(
    val records: List<T>,
    val pageCount: Int,
    val skippedWindows: Int = 0
)

/**
 * How far a sync has read one type, in the order of modification time and then record id: the
 * modification time of the last record it handled and, when the cap stopped inside a group of
 * records sharing that time, the id of the last one it took.
 *
 * Health Connect gives every record of one insert the same lastModifiedTime, so a watch that
 * uploads a backlog in one go writes thousands of records with a single time. A watermark of
 * time alone cannot stop inside such a group, so the cap had to take all of it and one payload
 * grew without bound, which is what #38 crashed on (F7 of P2-4). With the id it stops anywhere.
 *
 * [tieId] null means every record at [time] was handled. That is also what a watermark stored
 * before the id existed means, so those carry on unchanged.
 */
data class Watermark(val time: Instant, val tieId: String? = null) {
    /** Whether a record with this modification time and id comes after this mark, so is still to be read. */
    fun admits(modified: Instant, id: String): Boolean =
        modified > time || (modified == time && tieId != null && id > tieId)
}

/**
 * Pure sync/read logic, kept free of Health Connect types so it can be unit tested on the JVM.
 */
object ResilientReadLogic {

    val MIN_BISECT_WINDOW: Duration = Duration.ofMinutes(5)

    /** The order the cap and the [Watermark] share: modification time, then record id. */
    private fun <T> readOrder(timeOf: (T) -> Instant, idOf: (T) -> String): Comparator<T> =
        compareBy<T>({ timeOf(it) }, { idOf(it) })

    /**
     * Caps [records] to exactly [maxLimit], keeping the first in [readOrder]: the oldest by
     * modification time, and by id among records sharing one. Every record left out comes
     * after every record kept in that order, so the [Watermark] of the kept batch never skips
     * one (issue #38). Before the id was part of the order, the batch had to take every
     * record sharing the boundary time and could not be bounded (F7 of P2-4).
     */
    fun <T> capOldestFirst(records: List<T>, maxLimit: Int, timeOf: (T) -> Instant, idOf: (T) -> String): List<T> {
        if (records.size <= maxLimit) return records
        return records.sortedWith(readOrder(timeOf, idOf)).take(maxLimit)
    }

    /**
     * Caps sample-carrying records (heart rate, skin temperature) in [readOrder] at RECORD
     * granularity: whole records are included until the running sample count reaches
     * [maxSamples]. A record is either fully delivered or fully deferred, so a batch can
     * exceed [maxSamples] by at most one record's samples, and the same [Watermark] guarantee
     * as [capOldestFirst] holds.
     */
    fun <T> capRecordsBySamples(
        records: List<T>,
        maxSamples: Int,
        samplesOf: (T) -> Int,
        timeOf: (T) -> Instant,
        idOf: (T) -> String
    ): List<T> {
        val included = mutableListOf<T>()
        var sampleCount = 0
        for (record in records.sortedWith(readOrder(timeOf, idOf))) {
            if (sampleCount >= maxSamples) break
            included += record
            sampleCount += samplesOf(record)
        }
        return included
    }

    /**
     * Splits records that are new since the watermark into the ones this app wrote itself and
     * the rest (Receive, issue #62). What the app wrote came from Home Assistant; sending it
     * back would be an echo. [isOwn] is [isReceiveWrite]: the app's package and a client record id.
     */
    fun <T> partitionOwn(records: List<T>, isOwn: (T) -> Boolean): Pair<List<T>, List<T>> =
        records.partition(isOwn)

    /**
     * Whether a record is one Receive wrote: this app's package as the data origin and a
     * client record id, which Receive always sets (the integration's id for the reading).
     * The package alone is not enough: the debug seeder writes under the same package
     * without a client id, and its week of data is meant to be read and sent like any
     * other source's.
     */
    fun isReceiveWrite(dataOrigin: String, ownPackage: String, clientRecordId: String?): Boolean =
        dataOrigin == ownPackage && !clientRecordId.isNullOrEmpty()

    /**
     * The watermark to store after a read.
     *
     * Not capped: the newest modification time of the delivered batch and of the skipped own
     * records, with no id, since every record up to and at that time was handled. A skipped own
     * record would otherwise stay above the watermark and be read and counted again on every
     * sync for the whole lookback window.
     *
     * Capped: the last delivered record in [readOrder], time and id, so the next read starts
     * right after it, inside a group sharing its time if the cap stopped there. The own records
     * are ignored: a foreign record held back by the cap could sit between the delivered batch
     * and the newest own record, and moving past it would lose it.
     *
     * Null when there is nothing to advance to.
     */
    fun <T> watermarkAfter(
        delivered: List<T>,
        own: List<T>,
        capped: Boolean,
        timeOf: (T) -> Instant,
        idOf: (T) -> String
    ): Watermark? {
        if (capped) {
            val last = delivered.maxWithOrNull(readOrder(timeOf, idOf)) ?: return null
            return Watermark(timeOf(last), idOf(last))
        }
        val newest = (delivered + own).maxOfOrNull(timeOf) ?: return null
        return Watermark(newest)
    }

    /**
     * Reads a window via [read], falling back to recursive bisection when the reader throws
     * "startTime must be before endTime". Some source apps (e.g. Zepp for Amazfit devices) write
     * interval records with startTime == endTime; the Health Connect client rejects such a record
     * while materializing the read response, which would otherwise fail the entire type
     * (issue #12). Only the smallest sub-window still containing a malformed record is dropped,
     * so one bad record costs at most [minWindow] of data instead of the whole read.
     *
     * Records overlapping a split point are returned by both halves; [idOf] dedupes them.
     */
    suspend fun <T> readResilient(
        startTime: Instant,
        endTime: Instant,
        minWindow: Duration = MIN_BISECT_WINDOW,
        idOf: (T) -> Any,
        read: suspend (Instant, Instant) -> PagedResult<T>
    ): PagedResult<T> {
        return try {
            read(startTime, endTime)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("startTime must be before endTime") != true) throw e
            if (Duration.between(startTime, endTime) <= minWindow) {
                return PagedResult(emptyList(), 0, skippedWindows = 1)
            }
            val mid = startTime.plus(Duration.between(startTime, endTime).dividedBy(2))
            val first = readResilient(startTime, mid, minWindow, idOf, read)
            val second = readResilient(mid, endTime, minWindow, idOf, read)
            PagedResult(
                records = (first.records + second.records).distinctBy(idOf),
                pageCount = first.pageCount + second.pageCount,
                skippedWindows = first.skippedWindows + second.skippedWindows
            )
        }
    }
}
