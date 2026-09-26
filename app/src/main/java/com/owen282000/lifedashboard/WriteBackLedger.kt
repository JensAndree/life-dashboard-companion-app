package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app has written into Health Connect through Receive (issue #62), per reading id:
 * the highest version written, the metadata id Health Connect gave the record, and when.
 *
 * Three questions are answered from it. Whether a reading offered again needs writing (only
 * with a version higher than the one known, otherwise it is acknowledged and left alone);
 * which deletions in the change feed are the app's own records, so they are not echoed back
 * to Home Assistant; and, capped at [MAX_ENTRIES] with the oldest dropped first, how much of
 * that history to keep. It lives in its own preferences file, outside Android's backup, and
 * is cleared when the source URL or the secret changes: the integration then offers whatever
 * was not acknowledged again, and the upsert makes that harmless.
 *
 * Immutable: every change returns a new ledger, which keeps it trivially testable.
 */
@Serializable
data class WriteBackLedger(val entries: List<LedgerEntry> = emptyList()) {

    @Serializable
    data class LedgerEntry(
        val id: String,
        val version: Long,
        /** metadata.id of the record in Health Connect. */
        val recordId: String,
        val writtenAt: Long
    )

    private val byId: Map<String, LedgerEntry> by lazy { entries.associateBy { it.id } }

    /** The highest version written for [id], or null when the app never wrote it. */
    fun knownVersion(id: String): Long? = byId[id]?.version

    /** A reading needs writing only when its version is above the one written before. */
    fun shouldWrite(id: String, version: Long): Boolean = version > (knownVersion(id) ?: 0L)

    /** The metadata ids of every record the app wrote, for the deletion step to skip. */
    val ownRecordIds: Set<String> get() = entries.mapTo(mutableSetOf()) { it.recordId }

    /**
     * This ledger with [id] recorded at [version]. An id written before moves to the end with
     * its new version; the oldest entries beyond [MAX_ENTRIES] are dropped.
     */
    fun record(id: String, version: Long, recordId: String, writtenAt: Long): WriteBackLedger {
        val kept = entries.filter { it.id != id } + LedgerEntry(id, version, recordId, writtenAt)
        return WriteBackLedger(kept.takeLast(MAX_ENTRIES))
    }

    /** [record] for several readings at once, in the order given. */
    fun recordAll(written: List<LedgerEntry>): WriteBackLedger =
        written.fold(this) { ledger, entry -> ledger.record(entry.id, entry.version, entry.recordId, entry.writtenAt) }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_ENTRIES = 5000

        val EMPTY = WriteBackLedger()

        private val json = Json { ignoreUnknownKeys = true }

        /** Anything unreadable is an empty ledger: the worst case is one harmless rewrite. */
        fun decode(text: String?): WriteBackLedger =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: EMPTY
    }
}
