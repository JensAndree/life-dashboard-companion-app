package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteBackLedgerTest {

    @Test
    fun `a reading is written once and again only with a higher version`() {
        val empty = WriteBackLedger.EMPTY
        assertTrue(empty.shouldWrite("sensor.w@1", 1))
        assertNull(empty.knownVersion("sensor.w@1"))

        val ledger = empty.record("sensor.w@1", 2, "uuid-1", 1000)
        assertEquals(2L, ledger.knownVersion("sensor.w@1"))
        assertFalse("the same version is acknowledged, not written", ledger.shouldWrite("sensor.w@1", 2))
        assertFalse("an older version is acknowledged, not written", ledger.shouldWrite("sensor.w@1", 1))
        assertTrue("a correction is written", ledger.shouldWrite("sensor.w@1", 3))
        assertTrue("another id is unknown", ledger.shouldWrite("sensor.w@2", 1))
    }

    @Test
    fun `recording an id again replaces its entry instead of adding a second one`() {
        val ledger = WriteBackLedger.EMPTY
            .record("a", 1, "uuid-a", 1)
            .record("b", 1, "uuid-b", 2)
            .record("a", 2, "uuid-a", 3)
        assertEquals(listOf("b", "a"), ledger.entries.map { it.id })
        assertEquals(2L, ledger.knownVersion("a"))
    }

    @Test
    fun `the record ids are the ones the deletion step skips`() {
        val ledger = WriteBackLedger.EMPTY.record("a", 1, "uuid-a", 1).record("b", 1, "uuid-b", 2)
        assertEquals(setOf("uuid-a", "uuid-b"), ledger.ownRecordIds)
    }

    @Test
    fun `the cap drops the oldest entries first`() {
        var ledger = WriteBackLedger.EMPTY
        for (i in 1..(WriteBackLedger.MAX_ENTRIES + 3)) {
            ledger = ledger.record("id-$i", 1, "uuid-$i", i.toLong())
        }
        assertEquals(WriteBackLedger.MAX_ENTRIES, ledger.entries.size)
        assertNull(ledger.knownVersion("id-1"))
        assertNull(ledger.knownVersion("id-3"))
        assertEquals(1L, ledger.knownVersion("id-4"))
        assertEquals(1L, ledger.knownVersion("id-${WriteBackLedger.MAX_ENTRIES + 3}"))
    }

    @Test
    fun `recordAll applies entries in order`() {
        val ledger = WriteBackLedger.EMPTY.recordAll(
            listOf(
                WriteBackLedger.LedgerEntry("a", 1, "uuid-a", 1),
                WriteBackLedger.LedgerEntry("a", 3, "uuid-a", 2)
            )
        )
        assertEquals(1, ledger.entries.size)
        assertEquals(3L, ledger.knownVersion("a"))
    }

    @Test
    fun `round-trips through JSON and reads garbage as empty`() {
        val ledger = WriteBackLedger.EMPTY.record("sensor.w@1", 2, "uuid-1", 1000)
        assertEquals(ledger, WriteBackLedger.decode(ledger.encode()))
        assertEquals(WriteBackLedger.EMPTY, WriteBackLedger.decode(null))
        assertEquals(WriteBackLedger.EMPTY, WriteBackLedger.decode("not json"))
    }

    @Test
    fun `the report keeps the latest outcome per id and merges without duplicates`() {
        val first = WriteBackReport(ack = listOf("a", "b"), failed = listOf(FailedReading("c", "hc_unavailable")))
        val second = WriteBackReport(ack = listOf("c"), failed = listOf(FailedReading("a", "permission_denied")))
        val merged = first.merge(second)
        assertEquals(listOf("b", "c"), merged.ack)
        assertEquals(listOf(FailedReading("a", "permission_denied")), merged.failed)
        assertEquals(first, first.merge(WriteBackReport.EMPTY))
        assertTrue(WriteBackReport.EMPTY.isEmpty)
        assertFalse(merged.isEmpty)
    }
}
