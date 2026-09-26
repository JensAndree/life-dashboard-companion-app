package com.owen282000.lifedashboard

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/** The request for the source URL of Receive (issue #62): the same payload with the `writeback` block in it. */
data class SourcePost(val url: String, val payload: String)

/**
 * What the source URL answered. Read for that URL only, and only up to the protocol's size
 * cap: [oversized] says the body was cut off there, in which case [body] is empty.
 */
class SourceResponse(
    val body: ByteArray,
    /** The X-Signature header of the response, or null when it had none. */
    val signature: String?,
    /** The X-Signature the app put on the request, which the response must name in in_reply_to. */
    val requestSignature: String?,
    val oversized: Boolean
)

/** The outcome of a delivery: success is the Result itself; this carries what the source URL said, if asked. */
data class WebhookOutcome(val sourceResponse: SourceResponse? = null)

class WebhookManager(
    private val webhookUrls: List<String>,
    private val context: Context? = null,
    private val dataType: String? = null,
    private val recordCount: Int? = null,
    private val logType: LogType = LogType.HEALTH_CONNECT,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val signingSecret: String? = null,
    /**
     * The one URL that gets the `writeback` block and whose response is read. Every other URL
     * gets the plain payload and its response body is never looked at, however it is signed.
     */
    private val source: SourcePost? = null,
    /**
     * False for the heartbeat of Receive: a request that carries no records is not a delivery,
     * so a successful one writes no log row and counts nowhere; a failed one is still logged,
     * because that is where the user looks when Receive stops.
     */
    private val logSuccess: Boolean = true
) {

    /**
     * Built per post, on the IO dispatcher: presenting a client certificate means loading it
     * from KeyChain, which blocks and must not run on the main thread.
     */
    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val alias = context?.let { PreferencesManager(it).clientCertAlias() }
        if (context != null && alias != null) {
            val setup = ClientCertSupport.sslSetup(context, alias)
            builder.sslSocketFactory(setup.socketFactory, setup.trustManager)
        }
        return builder.build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Posts the payload to EVERY configured webhook. The sync counts as delivered when at
     * least one webhook accepted it; per-URL outcomes are visible in the webhook logs.
     */
    suspend fun postData(jsonPayload: String): Result<WebhookOutcome> = withContext(Dispatchers.IO) {
        if (webhookUrls.isEmpty()) {
            return@withContext Result.failure<WebhookOutcome>(
                IllegalStateException("No webhook URLs configured")
            )
        }

        // Any failure to set up the client, not only the "certificate unavailable" IOException,
        // is logged against every URL: nothing was sent, and the log is where the user looks.
        val client = try {
            buildClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val timestamp = System.currentTimeMillis()
            val reason = e.message ?: e.javaClass.simpleName
            webhookUrls.forEach { logWebhookCall(it, timestamp, null, false, reason, jsonPayload) }
            return@withContext Result.failure<WebhookOutcome>(e)
        }

        var anySuccess = false
        var lastFailure: Exception? = null
        var sourceResponse: SourceResponse? = null

        for (url in webhookUrls) {
            val isSource = source != null && url == source.url
            val result = postToUrl(client, url, if (isSource) source.payload else jsonPayload, readResponse = isSource)
            if (result.isSuccess) {
                anySuccess = true
                if (isSource) sourceResponse = result.getOrNull()
            } else {
                lastFailure = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")
            }
        }

        if (anySuccess) {
            Result.success(WebhookOutcome(sourceResponse))
        } else {
            Result.failure(lastFailure ?: IOException("All webhook posts failed"))
        }
    }

    private suspend fun postToUrl(
        client: OkHttpClient,
        url: String,
        jsonPayload: String,
        readResponse: Boolean
    ): Result<SourceResponse?> {
        val timestamp = System.currentTimeMillis()

        // HTTPS by default; plain HTTP only after the user opted in for private networks.
        val allowHttp = context?.let { PreferencesManager(it).allowHttpWebhooks() } ?: false
        WebhookSupport.cleartextBlockReason(url, allowHttp)?.let { reason ->
            logWebhookCall(url, timestamp, null, false, reason, jsonPayload)
            return Result.failure(IOException(reason))
        }

        return try {
            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
            customHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }
            val requestSignature = if (!signingSecret.isNullOrBlank()) WebhookSupport.signature(jsonPayload, signingSecret) else null
            if (requestSignature != null) {
                requestBuilder.header(WebhookSupport.SIGNATURE_HEADER, requestSignature)
            }
            val request = requestBuilder.build()

            var lastException: Exception? = null
            var statusCode: Int? = null
            var errorMessage: String? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    client.newCall(request).execute().use { response ->
                        statusCode = response.code
                        if (response.isSuccessful) {
                            // Only the source URL's body is read, and only up to the cap; a
                            // body that is cut off is handed over as oversized rather than as
                            // a truncated document that would fail its signature anyway.
                            val sourceResponse = if (readResponse) readSourceResponse(response, requestSignature) else null
                            val note = if (attempt > 1) "Recovered on attempt $attempt of $MAX_RETRIES" else null
                            logWebhookCall(url, timestamp, statusCode, true, null, jsonPayload, note)
                            return Result.success(sourceResponse)
                        }
                        lastException = IOException("HTTP ${response.code}: ${response.message}")
                        errorMessage = "HTTP ${response.code}: ${response.message}"
                    }
                    // Client errors (401, 404, ...) will not change on retry; fail fast so the
                    // sync is not delayed by pointless backoff.
                    if (!WebhookSupport.isRetryable(statusCode)) {
                        logWebhookCall(
                            url, timestamp, statusCode, false,
                            "$errorMessage (permanent error, not retried)", jsonPayload
                        )
                        return Result.failure(lastException ?: IOException("Webhook post failed"))
                    }
                } catch (e: IOException) {
                    lastException = e
                    statusCode = null
                    errorMessage = e.message ?: e.javaClass.simpleName
                }

                if (attempt < MAX_RETRIES) {
                    // Exponential backoff between transient failures
                    val delayMs = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt - 1).toLong())
                    kotlinx.coroutines.delay(delayMs)
                }
            }

            logWebhookCall(
                url, timestamp, statusCode, false,
                "Failed after $MAX_RETRIES attempts (transient errors): $errorMessage", jsonPayload
            )
            Result.failure(lastException ?: IOException("Max retries exceeded"))
        } catch (e: Exception) {
            logWebhookCall(url, timestamp, null, false, e.message, jsonPayload)
            Result.failure(e)
        }
    }

    private fun readSourceResponse(response: okhttp3.Response, requestSignature: String?): SourceResponse {
        val limit = WriteBackPayload.MAX_BODY_BYTES
        val bytes = response.body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= limit) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        val oversized = bytes.size > limit
        return SourceResponse(
            body = if (oversized) ByteArray(0) else bytes,
            signature = response.header(WebhookSupport.SIGNATURE_HEADER),
            requestSignature = requestSignature,
            oversized = oversized
        )
    }

    private fun logWebhookCall(
        url: String,
        timestamp: Long,
        statusCode: Int?,
        success: Boolean,
        errorMessage: String?,
        rawPayload: String?,
        note: String? = null
    ) {
        context?.let {
            if (success && !logSuccess) return
            if (success) {
                LifetimeStats.recordDelivery(it, recordCount ?: 0, rawPayload?.length ?: 0, logType)
            }
            val preferencesManager = PreferencesManager(it)
            val log = WebhookLog(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                url = url,
                statusCode = statusCode,
                success = success,
                errorMessage = errorMessage,
                dataType = dataType,
                recordCount = recordCount,
                rawPayload = rawPayload,
                logType = logType.name,
                note = note
            )
            preferencesManager.addWebhookLog(log)
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }
}
