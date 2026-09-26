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
 * Pure sync/read logic, kept free of Health Connect types so it can be unit tested on the JVM.
 */
object ResilientReadLogic {

    val MIN_BISECT_WINDOW: Duration = Duration.ofMinutes(5)

    /**
     * Caps [records] to [maxLimit], keeping the OLDEST records, then extends the batch with
     * every record sharing the boundary timestamp. This guarantees that every dropped record is
     * strictly newer than every kept one, so advancing lastSync to the kept batch's maximum
     * timestamp and filtering with a strict '>' never skips a dropped record (issue #38: without
     * the tie extension, records sharing the boundary lastModifiedTime that fell just past the
     * cap were above the cap but not above the watermark, and were skipped forever).
     */
    fun <T> capOldestFirst(records: List<T>, maxLimit: Int, timeOf: (T) -> Instant): List<T> {
        if (records.size <= maxLimit) return records
        val sorted = records.sortedBy(timeOf)
        val boundary = timeOf(sorted[maxLimit - 1])
        var end = maxLimit
        while (end < sorted.size && timeOf(sorted[end]) == boundary) end++
        return sorted.take(end)
    }

    /**
     * Caps sample-carrying records (heart rate, skin temperature) oldest-first at RECORD
     * granularity: whole records are included until the running sample count reaches
     * [maxSamples], then the batch is extended with every record sharing the boundary
     * timestamp. A record is either fully delivered or fully deferred, and the same
     * strict-'>' watermark guarantee as [capOldestFirst] holds.
     */
    fun <T> capRecordsBySamples(
        records: List<T>,
        maxSamples: Int,
        samplesOf: (T) -> Int,
        timeOf: (T) -> Instant
    ): List<T> {
        val sorted = records.sortedBy(timeOf)
        val included = mutableListOf<T>()
        var sampleCount = 0
        for (record in sorted) {
            if (sampleCount >= maxSamples && timeOf(record) != timeOf(included.last())) break
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
     * The watermark to store after a read: the newest modification time of the delivered
     * batch, and of the skipped own records too when the type was not capped. A skipped own
     * record would otherwise stay above the watermark and be read and counted again on every
     * sync for the whole lookback window; and when nothing was held back by the cap, every
     * foreign record older than the newest own one has been delivered, so advancing past it
     * skips nothing. When the type was capped the own records are ignored: a foreign record
     * held back by the cap could sit between the delivered batch and the newest own record,
     * and moving past it would lose it. Null when there is nothing to advance to.
     */
    fun <T> watermarkAfter(delivered: List<T>, own: List<T>, capped: Boolean, timeOf: (T) -> Instant): Instant? =
        listOfNotNull(
            delivered.maxOfOrNull(timeOf),
            if (capped) null else own.maxOfOrNull(timeOf)
        ).maxOrNull()

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
