package com.owen282000.lifedashboard.harness

import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.ScreenTimeManager

/**
 * Screen time the app can report. The app leaves out anything used for a minute or less, and
 * the launcher and System UI never count, so a fresh emulator has nothing to send. When today
 * has nothing yet, the Settings app is brought to the front until it passes the minute; on the
 * same AVD later that day it is there already and this returns at once.
 */
object ScreenTimeUse {

    fun allowUsageAccess() {
        val context = TestSetup.context
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
    }

    fun ensureToday(timeoutMs: Long = 150_000) {
        allowUsageAccess()
        if (hasToday()) return
        val context = TestSetup.context
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Await.until("a minute of foreground use today", timeoutMs, intervalMs = 2_000) { hasToday() }
    }

    private fun hasToday(): Boolean {
        val context = TestSetup.context
        return ScreenTimeManager(context, PreferencesManager(context)).readScreenTimeData(lookbackDays = 1)
            .getOrNull().orEmpty().isNotEmpty()
    }

    fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }
}
