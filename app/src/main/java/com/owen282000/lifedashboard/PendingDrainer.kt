package com.owen282000.lifedashboard

import android.content.Context

/**
 * Delivers queued outbox payloads using the CURRENT webhook configuration for each category,
 * so config changes made after a failure apply to the retried delivery too. Stops at the
 * first failure to preserve ordering; remaining items wait for the next drain (which runs at
 * the start of every sync). A payload the receiver refuses for good is dropped instead, see
 * [WebhookSupport.refusesPayload].
 */
object PendingDrainer {

    suspend fun drain(context: Context) {
        val store = PendingSyncStore.forContext(context)
        val items = store.peekAll()
        if (items.isEmpty()) return

        val preferencesManager = PreferencesManager(context)
        for (item in items) {
            val isScreenTime = item.logType == LogType.SCREEN_TIME.name
            val logType = if (isScreenTime) LogType.SCREEN_TIME else LogType.HEALTH_CONNECT
            val urls = if (isScreenTime) preferencesManager.getScreenTimeWebhookUrls()
                       else preferencesManager.getHealthWebhookUrls()
            if (urls.isEmpty()) continue

            val webhookManager = WebhookManager(
                webhookUrls = urls,
                context = context,
                dataType = item.dataType,
                recordCount = item.recordCount,
                logType = logType,
                customHeaders = if (isScreenTime) preferencesManager.getScreenTimeWebhookHeaders()
                                else preferencesManager.getHealthWebhookHeaders(),
                signingSecret = if (isScreenTime) preferencesManager.getScreenTimeWebhookSecret()
                                else preferencesManager.getHealthWebhookSecret()
            )

            val result = webhookManager.postData(item.payload)
            if (result.isSuccess) {
                store.remove(item.id)
                // A drained payload is a delivery like any other: it ends the failure streak
                // and moves "Last sync". Without this, an outage followed by a sync with no
                // new data left the failure notification and a red status in place while the
                // queued data had in fact arrived (F5 of P2-4). Its records count for today
                // now, since the failed attempt that queued it counted none.
                SyncFailureNotifier.recordResult(context, logType, true)
                SyncStatusStore.record(context, true, item.recordCount, logType)
            } else if (result.exceptionOrNull() is PayloadRefusedException) {
                // Refused for good (F6 of P2-4): it would never be accepted, and holding it
                // would keep everything behind it waiting. The log row says it was dropped.
                store.remove(item.id)
            } else {
                store.recordAttempt(item)
                break
            }
        }
    }
}
