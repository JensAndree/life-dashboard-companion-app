package com.owen282000.lifedashboard.fixture

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grants the fixture its Health Connect permissions and nothing else. scripts/instrumented.sh
 * runs it before the suite: ForeignSourceTest starts the fixture as a nested instrumentation,
 * which has no UiAutomation, so on a fresh emulator the fixture could not grant itself there.
 * Run on its own it can, and later runs find the permissions in place.
 */
@RunWith(AndroidJUnit4::class)
class GrantForSuite {

    @Test
    fun grantHealthConnectAccess() {
        println("Health Connect access: ${SelfGrant.ensure()}")
    }
}
