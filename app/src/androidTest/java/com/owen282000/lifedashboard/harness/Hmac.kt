package com.owen282000.lifedashboard.harness

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The suite's own HMAC code, deliberately not WebhookSupport: if the app got the signature
 * wrong (another label, uppercase hex, the secret as the response key, a re-serialised body),
 * a test that reused the app's function would be wrong in the same way and stay green.
 *
 * [check] anchors it to the known-answer vectors that the Home Assistant integration's own
 * tests carry (life-dashboard-ha, tests/test_payload.py, test_known_answer_vector and
 * test_response_key_is_derived_not_the_secret), so the app, the integration and this suite
 * are three implementations held to the same numbers.
 */
object Hmac {

    /** The literal label of protocol v1; spelled out here on purpose, not taken from the app. */
    private const val RESPONSE_LABEL = "life-dashboard-response-v1"

    fun raw(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** What the app must put in X-Signature on a request: HMAC(secret, raw body). */
    fun requestSignature(secret: String, body: ByteArray): String = "sha256=" + hex(raw(secret.toByteArray(), body))

    /** The key the integration signs its answer with: HMAC(secret, label), 32 raw bytes. */
    fun responseKey(secret: String): ByteArray = raw(secret.toByteArray(), RESPONSE_LABEL.toByteArray())

    /** What the integration puts in X-Signature on its answer. */
    fun responseSignature(secret: String, body: ByteArray): String = "sha256=" + hex(raw(responseKey(secret), body))

    /** Fails loudly when this code no longer matches the vectors of the integration's tests. */
    fun check() {
        val fox = "The quick brown fox jumps over the lazy dog".toByteArray()
        check(requestSignature("key", fox) == "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8") {
            "request signature vector"
        }
        check(hex(responseKey("key")) == "231a58ff1a4b95092f9b57457cebbb954207bafa208a00982abe19b95a0303c8") {
            "response key vector"
        }
        check(responseSignature("key", fox) == "sha256=1f5e6e7bf7761bb81dcbbb34f09b6ba176523cdeba9e80d9a28dce3f34791e9b") {
            "response signature vector"
        }
    }
}
