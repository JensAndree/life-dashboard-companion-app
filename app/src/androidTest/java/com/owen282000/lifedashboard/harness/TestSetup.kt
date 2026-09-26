package com.owen282000.lifedashboard.harness

import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences

/**
 * The app's settings for a test, written through the app's own PreferencesManager: one health
 * webhook on the [Receiver], a signing secret and headers of its own, plain HTTP allowed (the
 * receiver is http://127.0.0.1), MQTT off, Receive off unless asked for.
 */
object TestSetup {
    const val HEALTH_PATH = "/api/webhook/ci-health"
    const val HEALTH_SECRET = "ci-secret-health"
    val HEALTH_HEADERS = mapOf("X-Api-Key" to "ci-key-health", "Cookie" to "session=ci")

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun health(receiver: Receiver, types: Set<HealthDataType>, receive: Set<WriteBackType> = emptySet()) {
        val prefs = context.appPreferences()
        val url = receiver.url(HEALTH_PATH)
        prefs.setHealthWebhookUrls(listOf(url))
        prefs.setHealthWebhookSecret(HEALTH_SECRET)
        prefs.setHealthWebhookHeaders(HEALTH_HEADERS)
        prefs.setAllowHttpWebhooks(true)
        prefs.setHealthEnabledDataTypes(types)
        if (receive.isNotEmpty()) {
            prefs.setReceiveSourceUrl(url)
            prefs.setReceiveTypes(receive)
            prefs.setReceiveEnabled(true)
        }
    }

    /** The sync as the app runs it, on a Health Connect client that counts its calls. */
    fun syncManager(): HealthSyncManager = HealthSyncManager(context, Managers.counting(context))

    fun versionName(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}
