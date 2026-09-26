package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Receive row's one-line summary names only the types that actually arrive. */
class ReceiveSummaryTest {

    private val on = ReceiveSettings(enabled = true, sourceUrl = "http://ha.local:8123/api/webhook/x")
    private val weight = WriteBackType.WEIGHT
    private val pressure = WriteBackType.BLOOD_PRESSURE
    private val offered = ReceiveStatus(configured = listOf(weight.key, pressure.key))

    @Test
    fun `off, or on without a source, reads as off`() {
        assertEquals(ReceiveSummary.Off, ReceiveSummary.of(ReceiveSettings(), offered, emptySet()))
        assertEquals(ReceiveSummary.Off, ReceiveSummary.of(on.copy(sourceUrl = null), offered, emptySet()))
    }

    @Test
    fun `before the first answer it waits for the types`() {
        assertEquals(ReceiveSummary.AwaitingTypes, ReceiveSummary.of(on, ReceiveStatus(), emptySet()))
    }

    @Test
    fun `once the types are known and none is switched on it asks to choose one`() {
        assertEquals(ReceiveSummary.ChooseType, ReceiveSummary.of(on, offered, emptySet()))
    }

    @Test
    fun `a switched on type without write permission is named as missing, not as received`() {
        val summary = ReceiveSummary.of(on.copy(types = setOf(weight)), offered, emptySet())
        assertEquals(ReceiveSummary.PermissionMissing(listOf(weight)), summary)
    }

    @Test
    fun `only granted and offered types are listed, with what was written today`() {
        val settings = on.copy(types = setOf(weight, pressure))
        val summary = ReceiveSummary.of(settings, offered.copy(writtenToday = 8), setOf(pressure.writePermission))
        assertEquals(ReceiveSummary.Receiving(listOf(pressure), 8), summary)
    }

    @Test
    fun `a type the integration no longer offers is left out`() {
        val settings = on.copy(types = setOf(weight, pressure))
        val onlyPressure = ReceiveStatus(configured = listOf(pressure.key))
        val granted = setOf(weight.writePermission, pressure.writePermission)
        assertEquals(ReceiveSummary.Receiving(listOf(pressure), 0), ReceiveSummary.of(settings, onlyPressure, granted))
    }
}
