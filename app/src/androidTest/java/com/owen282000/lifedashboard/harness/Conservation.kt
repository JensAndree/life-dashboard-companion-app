package com.owen282000.lifedashboard.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.fail

/**
 * The conservation law every delivery test asserts (P2-4 report 04): each record the test put
 * into Health Connect arrives exactly once over all delivered payloads, and nothing else
 * arrives. "A POST went out" is not enough; most of the sync's history is of payloads that
 * went out with something missing, doubled or extra.
 */
object Conservation {

    /** Arrays of a health payload that do not hold records of their own. */
    private val NOT_RECORDS = setOf("deleted_records", "daily_totals", "deletions_unavailable")

    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** Every record in [payload] as (array key, uuid, source). */
    fun records(payload: JsonObject): List<Triple<String, String?, String?>> =
        payload.entries.filter { it.key !in NOT_RECORDS && it.value is JsonArray }.flatMap { (key, value) ->
            (value as JsonArray).filterIsInstance<JsonObject>().map { record ->
                Triple(key, (record["uuid"] as? JsonPrimitive)?.content, (record["source"] as? JsonPrimitive)?.content)
            }
        }

    /** Fails unless the uuids over [delivered] are exactly [seeded], each exactly once. */
    fun assertExactlyOnce(seeded: Set<String>, delivered: List<JsonObject>) {
        val all = delivered.flatMap { records(it) }
        val counts = all.groupingBy { it.second }.eachCount()
        val missing = seeded.filter { it !in counts }
        val doubled = counts.filter { it.value > 1 }.keys
        val extra = all.filter { it.second !in seeded }
        if (missing.isEmpty() && doubled.isEmpty() && extra.isEmpty()) return
        fail(
            buildString {
                append("Conservation law broken over ${delivered.size} payload(s).")
                if (missing.isNotEmpty()) append("\n  never arrived: $missing")
                if (doubled.isNotEmpty()) append("\n  arrived more than once: $doubled")
                if (extra.isNotEmpty()) {
                    append("\n  arrived but not seeded (key, uuid, source): $extra")
                    if (extra.any { it.third != null && it.third != TestSetup.context.packageName }) {
                        append("\n  another app wrote to Health Connect on this device; the suite needs a clean ldc-instrumented AVD")
                    }
                }
            }
        )
    }
}
