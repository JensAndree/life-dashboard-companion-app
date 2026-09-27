package com.owen282000.lifedashboard

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure webhook helpers, kept free of Android/OkHttp types so they can be unit tested. */
object WebhookSupport {

    const val SIGNATURE_HEADER = "X-Signature"

    /**
     * HMAC-SHA256 signature header value for a payload: "sha256=<lowercase hex>". Receivers
     * verify by recomputing the HMAC over the raw request body with the shared secret and
     * comparing it (constant-time) against this header.
     */
    fun signature(payload: String, secret: String): String =
        "sha256=" + hex(hmac(secret.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8)))

    /**
     * The label the response key is derived under (write-back protocol v1, issue #62). The
     * integration signs its response with HMAC-SHA256 under a key that is itself
     * HMAC-SHA256(secret, this label), never under the secret directly, so a request the app
     * signed can never be played back to it as a response: the two directions use different
     * keys, and only one of them is ever seen on the wire in each direction.
     */
    const val RESPONSE_KEY_LABEL = "life-dashboard-response-v1"

    /** The 32 raw bytes the response direction is keyed with. */
    fun responseKey(secret: String): ByteArray =
        hmac(secret.toByteArray(Charsets.UTF_8), RESPONSE_KEY_LABEL.toByteArray(Charsets.UTF_8))

    /** What the X-Signature header on a response must equal, computed over the raw body bytes. */
    fun responseSignature(body: ByteArray, secret: String): String = "sha256=" + hex(hmac(responseKey(secret), body))

    /**
     * Constant-time comparison of two signature strings, so a byte-by-byte mismatch cannot be
     * timed. A missing header never matches anything.
     */
    fun signaturesMatch(presented: String?, expected: String): Boolean {
        if (presented == null) return false
        return java.security.MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Whether a failed delivery attempt is worth retrying. Network-level failures (no HTTP
     * status) and transient statuses are; client errors like 401 or 404 will not change on
     * retry and only delay the sync.
     */
    fun isRetryable(statusCode: Int?): Boolean {
        if (statusCode == null) return true
        return statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }

    /**
     * Whether a status says the receiver refuses this payload itself, so it will never be
     * accepted however often it is sent: 400, 413 and 422. Such a payload is dropped rather
     * than queued, because in the outbox it would hold back everything behind it until the
     * cap pushed it out (F6 of P2-4).
     *
     * Every other refusal is about the receiver's setup, not the payload: a wrong or missing
     * key (401, 403, 407), or a webhook that is gone or switched off (404, 405, 410, which is
     * what n8n answers for an inactive workflow). A correction makes the same payload welcome
     * again, and the drain posts with the current settings, so those stay queued, as do the
     * transient errors.
     */
    fun refusesPayload(statusCode: Int?): Boolean = statusCode == 400 || statusCode == 413 || statusCode == 422

    const val CLEARTEXT_BLOCKED_MESSAGE =
        "Plain HTTP is blocked. Enable \"Allow plain HTTP webhooks\" in the app for endpoints on a private LAN or VPN, or use HTTPS."

    /**
     * Why a URL must not be posted to, or null when it may. Cleartext is permitted at the
     * platform level (see network_security_config.xml) so the decision lives here, where it
     * is testable: http:// is only allowed after the user opted in (issue #51).
     */
    fun cleartextBlockReason(url: String, allowHttp: Boolean): String? {
        val isHttp = url.trim().startsWith("http://", ignoreCase = true)
        return if (isHttp && !allowHttp) CLEARTEXT_BLOCKED_MESSAGE else null
    }
}
