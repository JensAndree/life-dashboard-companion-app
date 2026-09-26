package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/*
 * Write-back protocol v1 (issue #62): what the app sends in the `writeback` block of its
 * request, how it checks the integration's response, and how it validates each reading
 * before it becomes a record. Pure Kotlin on purpose, so every rule here has a JVM test.
 * The Health Connect calls live in HealthConnectManager, the orchestration in WriteBackApplier.
 */

/** The closed set of codes the app reports back per reading in `writeback.failed`. */
enum class WriteBackFailure(val code: String, val retryable: Boolean) {
    /** The WRITE permission for the type is missing or was revoked. */
    PERMISSION_DENIED("permission_denied", false),

    /** A `type` this app version does not know. */
    UNSUPPORTED_TYPE("unsupported_type", false),

    /** A value outside the bounds in [WriteBackPayload.validate]. */
    OUT_OF_RANGE("out_of_range", false),

    /** Older than 30 days while "Accept older measurements" is off. */
    TOO_OLD("too_old", false),

    /** A field missing or not a number, a time in the future, or an unreadable reading. */
    INVALID("invalid", false),

    /** Health Connect's quota; the integration offers the reading again next round. */
    RATE_LIMITED("rate_limited", true),

    /** Health Connect did not answer or answered with an error; offered again next round. */
    HC_UNAVAILABLE("hc_unavailable", true)
}

/** Why a whole response was thrown away. Logged as one line; nothing is written. */
enum class WriteBackRejection(val reason: String) {
    MISSING_SIGNATURE("no signature on the response"),
    BAD_SIGNATURE("the signature does not match"),
    NOT_IN_REPLY("in_reply_to does not name this request"),
    STALE("issued_at is more than 10 minutes from the phone's clock"),
    TOO_LARGE("the body is larger than 256 KiB"),
    TOO_MANY("more than 200 readings"),
    MALFORMED("the body is not the expected JSON"),
    UNSUPPORTED_PROTOCOL("the response speaks a protocol version this app does not")
}

/** The verified shape of a response from the source URL. */
sealed class WriteBackResponse {
    /**
     * No protocol block at all: an integration older than 0.7.0, or a receiver that is not
     * the integration. The UI says "Update the Life Dashboard integration"; nothing is written.
     */
    data object Incompatible : WriteBackResponse()

    data class Rejected(val rejection: WriteBackRejection) : WriteBackResponse()

    data class Accepted(
        val integrationVersion: String?,
        /** The types the integration has a mapping for, so the UI offers only those. */
        val configured: List<String>,
        /** The readings as sent, oldest first, still to be validated one by one. */
        val readings: List<JsonObject>,
        /** More readings wait behind these; the app asks again in the same sync when there is time. */
        val more: Boolean
    ) : WriteBackResponse()
}

/** What validation made of one reading. */
sealed class ReadingOutcome {
    data class Ready(val reading: PendingReading) : ReadingOutcome()

    /**
     * A reading the app will not write. [id] is null when the reading did not even carry
     * one, in which case there is nothing to report back under `failed`.
     */
    data class Refused(
        val id: String?,
        val failure: WriteBackFailure,
        val typeKey: String? = null,
        val time: Instant? = null
    ) : ReadingOutcome()
}

/** One entry of `writeback.failed`: an id and a code, never a value. */
@Serializable
data class FailedReading(val id: String, val code: String)

/**
 * The acks and failures that still have to reach the integration. They ride on the next
 * request to the source URL and are kept until that request has been accepted, so a lost
 * request loses no ack. A later outcome for the same id replaces the earlier one.
 */
@Serializable
data class WriteBackReport(
    val ack: List<String> = emptyList(),
    val failed: List<FailedReading> = emptyList()
) {
    val isEmpty: Boolean get() = ack.isEmpty() && failed.isEmpty()

    fun merge(other: WriteBackReport): WriteBackReport {
        if (other.isEmpty) return this
        val laterIds = other.ack.toSet() + other.failed.map { it.id }
        return WriteBackReport(
            ack = (ack.filter { it !in laterIds } + other.ack).distinct(),
            failed = (failed.filter { it.id !in laterIds } + other.failed).distinctBy { it.id }
        )
    }

    /** This report minus what [delivered] carried: the entries a request the integration accepted has taken along. */
    fun without(delivered: WriteBackReport): WriteBackReport {
        if (delivered.isEmpty) return this
        val gone = delivered.ack.toSet()
        val goneFailed = delivered.failed.toSet()
        return WriteBackReport(
            ack = ack.filter { it !in gone },
            failed = failed.filter { it !in goneFailed }
        )
    }

    companion object {
        val EMPTY = WriteBackReport()
    }
}

/** Which webhook URL of the section is the integration's, as far as the app can tell. */
sealed class SourceUrlChoice {
    /** No URL looks like a Home Assistant webhook, so Receive cannot be switched on. */
    data object None : SourceUrlChoice()

    data class One(val url: String) : SourceUrlChoice()

    /** Several do; the user picks. */
    data class Several(val urls: List<String>) : SourceUrlChoice()
}

object WriteBackPayload {

    const val PROTOCOL = 1
    const val MAX_BODY_BYTES = 262_144
    const val MAX_READINGS = 200

    /** How far `issued_at` may sit from the phone's clock, either way. */
    val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(10)

    /** A measurement may be slightly in the future (a clock ahead of the phone's), not more. */
    val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)

    /** Without "Accept older measurements", anything older than this is refused as too_old. */
    val HISTORY_WINDOW: Duration = Duration.ofDays(30)

    /** How many extra requests one sync may make to drain a `more: true` backlog. */
    const val MAX_FOLLOW_UPS = 5

    /** The path fragment that marks a Home Assistant webhook URL. */
    private const val HA_WEBHOOK_PATH = "/api/webhook/"

    private val json = Json { ignoreUnknownKeys = true }

    /** The `writeback` block of a request to the source URL (section 3.1 of the protocol). */
    fun requestBlock(
        types: Set<WriteBackType>,
        history: Boolean,
        report: WriteBackReport
    ): JsonObject = buildJsonObject {
        put("protocol", PROTOCOL)
        put("types", buildJsonArray { WriteBackType.entries.filter { it in types }.forEach { add(JsonPrimitive(it.key)) } })
        put("history", history)
        put("ack", buildJsonArray { report.ack.forEach { add(JsonPrimitive(it)) } })
        put(
            "failed",
            buildJsonArray {
                report.failed.forEach { add(buildJsonObject { put("id", it.id); put("code", it.code) }) }
            }
        )
    }

    /**
     * Whether a sync should talk write-back at all: the switch is on, the chosen source URL
     * is still one of the section's URLs, and the section has a signing secret. Without the
     * secret the response could not be verified, and without the URL there is nobody to ask.
     */
    fun isActive(enabled: Boolean, sourceUrl: String?, urls: List<String>, secret: String?): Boolean =
        enabled && sourceUrl != null && sourceUrl in urls && !secret.isNullOrBlank()

    /** Picks the integration's URL from the section: the one that looks like a Home Assistant webhook. */
    fun sourceUrlChoice(urls: List<String>): SourceUrlChoice {
        val candidates = urls.filter { it.contains(HA_WEBHOOK_PATH) }.distinct()
        return when (candidates.size) {
            0 -> SourceUrlChoice.None
            1 -> SourceUrlChoice.One(candidates.single())
            else -> SourceUrlChoice.Several(candidates)
        }
    }

    /**
     * Checks a response from the source URL in the order the protocol prescribes and stops at
     * the first problem: size, signature, protocol, `in_reply_to`, `issued_at`, count. A
     * response that fails any of these writes nothing and is logged as one line.
     *
     * The size is checked before the signature because the transport already stops reading
     * at the cap; verifying a signature over a truncated body would only fail more slowly.
     */
    fun verify(
        body: ByteArray,
        signatureHeader: String?,
        secret: String,
        requestSignature: String,
        now: Instant,
        oversized: Boolean = false
    ): WriteBackResponse {
        if (oversized || body.size > MAX_BODY_BYTES) return WriteBackResponse.Rejected(WriteBackRejection.TOO_LARGE)
        val text = String(body, Charsets.UTF_8)
        if (signatureHeader.isNullOrBlank()) {
            // An integration from before the protocol answers 200 with nothing, and a plain
            // receiver answers whatever it likes: neither is an attack, both mean "not this
            // protocol". A body that does carry the block but no signature is refused.
            return if (hasProtocolBlock(text)) WriteBackResponse.Rejected(WriteBackRejection.MISSING_SIGNATURE)
            else WriteBackResponse.Incompatible
        }
        if (!WebhookSupport.signaturesMatch(signatureHeader, WebhookSupport.responseSignature(body, secret))) {
            return WriteBackResponse.Rejected(WriteBackRejection.BAD_SIGNATURE)
        }

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return WriteBackResponse.Rejected(WriteBackRejection.MALFORMED)
        val announced = root["life_dashboard"]?.let { it as? JsonObject }
        val protocol = announced?.get("writeback")?.let { (it as? JsonPrimitive)?.longOrNull }
            ?: return WriteBackResponse.Incompatible
        if (protocol != PROTOCOL.toLong()) return WriteBackResponse.Rejected(WriteBackRejection.UNSUPPORTED_PROTOCOL)

        val block = root["writeback"]?.let { it as? JsonObject }
            ?: return WriteBackResponse.Rejected(WriteBackRejection.MALFORMED)
        val inReplyTo = block.string("in_reply_to")
        if (inReplyTo == null || !WebhookSupport.signaturesMatch(inReplyTo, requestSignature)) {
            return WriteBackResponse.Rejected(WriteBackRejection.NOT_IN_REPLY)
        }
        val issuedAt = block.string("issued_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return WriteBackResponse.Rejected(WriteBackRejection.MALFORMED)
        if (Duration.between(issuedAt, now).abs() > MAX_CLOCK_SKEW) {
            return WriteBackResponse.Rejected(WriteBackRejection.STALE)
        }

        val readings = block["pending"]?.let { pending ->
            (pending as? kotlinx.serialization.json.JsonArray)?.map { it as? JsonObject ?: JsonObject(emptyMap()) }
                ?: return WriteBackResponse.Rejected(WriteBackRejection.MALFORMED)
        } ?: emptyList()
        if (readings.size > MAX_READINGS) return WriteBackResponse.Rejected(WriteBackRejection.TOO_MANY)

        val configured = block["configured"]?.let { list ->
            (list as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } ?: emptyList()
        return WriteBackResponse.Accepted(
            integrationVersion = announced.string("version"),
            configured = configured,
            readings = readings,
            more = block["more"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
        )
    }

    private fun hasProtocolBlock(text: String): Boolean =
        runCatching { json.parseToJsonElement(text).jsonObject["life_dashboard"] is JsonObject }.getOrDefault(false)

    /**
     * Validates one reading (section 8.2 of the protocol) and turns it into a [PendingReading].
     *
     * [requested] are the types the app asked for in this request and [granted] the ones whose
     * WRITE permission is held right now; a reading of any other type is permission_denied,
     * because the permission can be revoked between the request and the answer. Bounds are
     * inclusive; a value of exactly zero is never in range, since every lower bound is above it.
     */
    fun validate(
        raw: JsonObject,
        requested: Set<WriteBackType>,
        granted: Set<WriteBackType>,
        now: Instant,
        acceptOlder: Boolean
    ): ReadingOutcome {
        val id = raw.string("id")?.takeIf { it.isNotBlank() }
            ?: return ReadingOutcome.Refused(null, WriteBackFailure.INVALID)
        val typeKey = raw.string("type")
        val time = raw.string("time")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        fun refused(failure: WriteBackFailure) = ReadingOutcome.Refused(id, failure, typeKey, time)

        val version = raw.long("version") ?: return refused(WriteBackFailure.INVALID)
        if (version < 1) return refused(WriteBackFailure.INVALID)
        val type = typeKey?.let { WriteBackType.fromKey(it) } ?: return refused(WriteBackFailure.UNSUPPORTED_TYPE)
        if (type !in requested || type !in granted) return refused(WriteBackFailure.PERMISSION_DENIED)
        if (time == null) return refused(WriteBackFailure.INVALID)
        if (time > now.plus(FUTURE_TOLERANCE)) return refused(WriteBackFailure.INVALID)
        if (!acceptOlder && time < now.minus(HISTORY_WINDOW)) return refused(WriteBackFailure.TOO_OLD)

        val value: Double
        var diastolic: Double? = null
        when (type) {
            WriteBackType.WEIGHT, WriteBackType.LEAN_BODY_MASS, WriteBackType.BONE_MASS, WriteBackType.BODY_WATER_MASS ->
                value = raw.number("kilograms") ?: return refused(WriteBackFailure.INVALID)
            WriteBackType.HEIGHT -> value = raw.number("meters") ?: return refused(WriteBackFailure.INVALID)
            WriteBackType.BODY_FAT -> value = raw.number("percentage") ?: return refused(WriteBackFailure.INVALID)
            WriteBackType.BLOOD_PRESSURE -> {
                value = raw.number("systolic") ?: return refused(WriteBackFailure.INVALID)
                diastolic = raw.number("diastolic") ?: return refused(WriteBackFailure.INVALID)
            }
        }
        if (!inRange(type, value, diastolic)) return refused(WriteBackFailure.OUT_OF_RANGE)

        val device = raw["device"]?.let { it as? JsonObject }?.let {
            ReadingDevice(
                type = it.string("type") ?: "unknown",
                manufacturer = it.string("manufacturer"),
                model = it.string("model")
            )
        }
        return ReadingOutcome.Ready(
            PendingReading(
                id = id,
                version = version,
                type = type,
                value = value,
                diastolic = diastolic,
                time = time,
                zoneOffset = raw.string("zone_offset")?.let { runCatching { ZoneOffset.of(it) }.getOrNull() },
                recordingMethod = RecordingMethod.fromKey(raw.string("recording_method")),
                device = device,
                bodyPosition = raw.string("body_position") ?: "unknown",
                measurementLocation = raw.string("measurement_location") ?: "unknown",
                timeSource = raw.string("time_source")
            )
        )
    }

    /** The plausibility bounds per type, inclusive. Diastolic must also stay below systolic. */
    fun inRange(type: WriteBackType, value: Double, diastolic: Double? = null): Boolean = when (type) {
        WriteBackType.WEIGHT -> value in 1.0..500.0
        WriteBackType.HEIGHT -> value in 0.3..2.8
        WriteBackType.BODY_FAT -> value in 1.0..80.0
        WriteBackType.LEAN_BODY_MASS -> value in 1.0..300.0
        WriteBackType.BONE_MASS -> value in 0.1..30.0
        WriteBackType.BODY_WATER_MASS -> value in 1.0..300.0
        WriteBackType.BLOOD_PRESSURE ->
            value in 30.0..300.0 && diastolic != null && diastolic in 10.0..250.0 && diastolic < value
    }

    /**
     * The failure code for an exception out of Health Connect. A SecurityException is the
     * permission gone; an IllegalArgumentException comes from a record constructor refusing
     * the value; the client reports its quota as a RemoteException whose message says so, and
     * everything else, including a time-out, is Health Connect not being there right now.
     */
    fun failureFor(error: Throwable): WriteBackFailure = when {
        error is SecurityException -> WriteBackFailure.PERMISSION_DENIED
        error is IllegalArgumentException -> WriteBackFailure.OUT_OF_RANGE
        error.message?.let { it.contains("quota", ignoreCase = true) || it.contains("rate limit", ignoreCase = true) } == true ->
            WriteBackFailure.RATE_LIMITED
        else -> WriteBackFailure.HC_UNAVAILABLE
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    /** For the log: the readings of a response as `type` keys, so the row can say what was offered without values. */
    fun typeKeysOf(readings: List<JsonObject>): List<String> = readings.mapNotNull { it.string("type") }
}
