package com.owen282000.lifedashboard

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * Deletion propagation (issue #61).
 *
 * A sync reads records and filters them on metadata.lastModifiedTime. A deletion produces no
 * record at all, so it is invisible to that read: a receiver keeps a record that Health Connect
 * no longer has. Cronometer, for instance, replaces a meal by deleting it and inserting a new
 * one, which leaves the receiver holding both.
 *
 * Health Connect answers this with a changes token per record type. [ChangesTracker] walks the
 * changes since the stored token and reports the ids of deleted records; the payload carries
 * them as `deleted_records` so a receiver can drop exactly those ids.
 *
 * This file holds the parts that are decidable without Health Connect, so they can be unit
 * tested: what a token is worth, and how the result is shaped. The client calls live in
 * [HealthConnectManager].
 */

/** One deleted record, as it appears in the payload. */
@kotlinx.serialization.Serializable
data class DeletedRecord(
    /** The payload key of the type it belonged to, e.g. "nutrition". */
    val type: String,
    /** metadata.id of the record Health Connect deleted. */
    val uuid: String
)

/**
 * The outcome of reading changes for one type.
 *
 * [expired] says the stored token was no longer accepted, which Health Connect does after about
 * 30 days without a sync. Deletions in that gap are lost, and only a backfill snapshot can
 * reconcile them, so the sync reports it rather than silently continuing.
 */
data class ChangesResult(
    val deleted: List<DeletedRecord> = emptyList(),
    val nextToken: String? = null,
    val expired: Boolean = false,
    val error: String? = null,
    /** Records the feed reported as written or edited before the range the sync reads, see [OutsideWindow]. */
    val outsideWindow: OutsideWindow? = null,
    /**
     * Every id the feed reported as written, as the record was when its page was read: proof
     * that the record existed then. A deletion stored from an earlier sync for one of these ids
     * is stale, see [DeletionSummary.without] (issues #71, #72).
     */
    val upserted: Set<String> = emptySet()
)

/**
 * One page of a type's changes feed, as [DeletionTracking.standingDeletions] reads it: the ids
 * the page reported as written and the ids it reported as deleted.
 */
data class FeedPage(val upserted: Set<String>, val deleted: List<String>)

/**
 * What one sync found across every enabled type: the deletions to publish, the types whose
 * token had expired so a receiver knows where a deletion could have been missed, and the
 * records the feed named that the read could not see.
 *
 * Reading the changes feed consumes it, so a summary that was read but not delivered cannot be
 * read again. It is therefore carried across syncs until a payload actually goes out, the same
 * way still-open buckets are (see `getBucketCarry`); [merge] joins the carried summary with what
 * the current sync found.
 */
@kotlinx.serialization.Serializable
data class DeletionSummary(
    val deleted: List<DeletedRecord> = emptyList(),
    val expiredTypes: List<String> = emptyList(),
    /** Per payload key, what the feed named that the read could not see, see [OutsideWindow]. */
    val outsideWindow: Map<String, OutsideWindow> = emptyMap()
) {
    val isEmpty: Boolean get() = deleted.isEmpty() && expiredTypes.isEmpty() && outsideWindow.isEmpty()

    /**
     * This summary plus [other], without duplicates and in the same stable order a single sync
     * would produce, so a deletion carried from an earlier sync is indistinguishable from a
     * fresh one.
     */
    fun merge(other: DeletionSummary): DeletionSummary {
        if (other.isEmpty) return this
        if (isEmpty) return other
        return DeletionSummary(
            deleted = (deleted + other.deleted)
                .distinctBy { it.type to it.uuid }
                .sortedWith(compareBy({ it.type }, { it.uuid })),
            expiredTypes = (expiredTypes + other.expiredTypes).distinct().sorted(),
            outsideWindow = (outsideWindow.keys + other.outsideWindow.keys).sorted().associateWith { type ->
                listOfNotNull(outsideWindow[type], other.outsideWindow[type]).reduce(OutsideWindow::plus)
            }
        )
    }

    /**
     * This summary without the deletions of [ids], per payload key: records that exist after
     * all. The other fields stay, since they say something about a type, not about a record.
     */
    fun without(ids: Map<String, Set<String>>): DeletionSummary {
        if (ids.values.all { it.isEmpty() }) return this
        return copy(deleted = deleted.filterNot { it.uuid in ids[it.type].orEmpty() })
    }

    /**
     * What a payload can carry now, and what has to wait. A deletion of a record that goes out
     * in the same payload is dropped: the record exists, it was read after the deletion was. A
     * deletion for a type in [holdTypes] waits, because that type's backlog is still being read
     * and the record may come back in a later pass. [delivered] is per payload key, see
     * [DeletionTracking.recordIds]. The fields about types always go out.
     */
    fun split(delivered: Map<String, Set<String>>, holdTypes: Set<String>): Pair<DeletionSummary, DeletionSummary> {
        val live = without(delivered)
        val (held, send) = live.deleted.partition { it.type in holdTypes }
        return live.copy(deleted = send) to DeletionSummary(deleted = held)
    }

    companion object {
        val EMPTY = DeletionSummary()
    }
}

object DeletionTracking {

    /**
     * The deletion fields of a payload, as [HealthSyncManager] writes them.
     *
     * Kept here rather than inline so the shape a receiver parses can be asserted directly:
     * these fields instruct a receiver to remove data it already stored, so a wrong key or a
     * field that appears when it should not is destructive rather than cosmetic.
     *
     * Absent beats empty throughout: no `deleted_records` says nothing was deleted, while an
     * empty array would say the app looked and found none, and those are different claims.
     */
    fun payloadFields(summary: DeletionSummary): Map<String, JsonElement> = buildMap {
        if (summary.deleted.isNotEmpty()) {
            put(
                "deleted_records",
                buildJsonArray {
                    summary.deleted.forEach { record ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive(record.type))
                                put("uuid", JsonPrimitive(record.uuid))
                            }
                        )
                    }
                }
            )
        }
        if (summary.expiredTypes.isNotEmpty()) {
            put(
                "deletions_unavailable",
                buildJsonArray { summary.expiredTypes.forEach { add(JsonPrimitive(it)) } }
            )
        }
        if (summary.outsideWindow.isNotEmpty()) {
            put(
                "records_outside_window",
                buildJsonObject {
                    summary.outsideWindow.toSortedMap().forEach { (type, outside) ->
                        put(
                            type,
                            buildJsonObject {
                                put("count", JsonPrimitive(outside.count))
                                put("from", JsonPrimitive(Instant.ofEpochMilli(outside.fromMs).toString()))
                                put("until", JsonPrimitive(Instant.ofEpochMilli(outside.untilMs).toString()))
                            }
                        )
                    }
                }
            )
        }
    }

    /**
     * Health Connect expires a changes token after 30 days of not being used. The app treats a
     * token older than this as expired without asking, so a phone that has not synced for a
     * month starts a fresh token instead of spending a call on a certain rejection.
     */
    const val TOKEN_MAX_AGE_DAYS = 30L

    /**
     * How long one type's changes read may take, and how long the whole deletion step may take
     * across every enabled type.
     *
     * The step runs before the first delivery and does one round trip to Health Connect per
     * enabled type, up to 33. Warm, that is milliseconds each. A scheduled run that starts
     * while the phone is dozing can find the service cold and a call that does not return, and
     * a hang is not an exception, so without a bound the whole sync would sit there until
     * Android stopped the worker, which delivers nothing at all. A type that does not fit keeps
     * its token, so the next sync reads it from the same position; it only costs that type a
     * turn in `deletions_unavailable`, which is the honest report (1.18.1, after a report of
     * background syncs stalling on 1.18.0).
     */
    const val PER_TYPE_TIMEOUT_MS = 5_000L
    const val TOTAL_BUDGET_MS = 20_000L

    /**
     * How long the next type may take given how much of the budget is already spent: the
     * per-type limit, or whatever is left of the total if that is less, or zero when the total
     * is gone, which the caller reads as "skip this type".
     */
    fun timeoutFor(
        elapsedMs: Long,
        perTypeMs: Long = PER_TYPE_TIMEOUT_MS,
        totalMs: Long = TOTAL_BUDGET_MS
    ): Long = minOf(perTypeMs, totalMs - elapsedMs).coerceAtLeast(0)

    /**
     * The payload key each type's records are published under, which is also the key a receiver
     * stores them by. `deleted_records` uses these names so an entry points at the same
     * collection the record itself arrived in.
     */
    fun payloadKey(type: HealthDataType): String = when (type) {
        HealthDataType.STEPS -> "steps"
        HealthDataType.SLEEP -> "sleep"
        HealthDataType.HEART_RATE -> "heart_rate"
        HealthDataType.DISTANCE -> "distance"
        HealthDataType.ACTIVE_CALORIES -> "active_calories"
        HealthDataType.TOTAL_CALORIES -> "total_calories"
        HealthDataType.WEIGHT -> "weight"
        HealthDataType.HEIGHT -> "height"
        HealthDataType.BLOOD_PRESSURE -> "blood_pressure"
        HealthDataType.BLOOD_GLUCOSE -> "blood_glucose"
        HealthDataType.OXYGEN_SATURATION -> "oxygen_saturation"
        HealthDataType.BODY_TEMPERATURE -> "body_temperature"
        HealthDataType.RESPIRATORY_RATE -> "respiratory_rate"
        HealthDataType.RESTING_HEART_RATE -> "resting_heart_rate"
        HealthDataType.EXERCISE -> "exercise"
        HealthDataType.HYDRATION -> "hydration"
        HealthDataType.NUTRITION -> "nutrition"
        HealthDataType.MINDFULNESS -> "mindfulness"
        HealthDataType.BODY_FAT -> "body_fat"
        HealthDataType.LEAN_BODY_MASS -> "lean_body_mass"
        HealthDataType.BONE_MASS -> "bone_mass"
        HealthDataType.BODY_WATER_MASS -> "body_water_mass"
        HealthDataType.HEART_RATE_VARIABILITY -> "heart_rate_variability"
        HealthDataType.MENSTRUATION_PERIOD -> "menstruation_period"
        HealthDataType.MENSTRUATION_FLOW -> "menstruation_flow"
        HealthDataType.BASAL_METABOLIC_RATE -> "basal_metabolic_rate"
        HealthDataType.VO2_MAX -> "vo2_max"
        HealthDataType.SKIN_TEMPERATURE -> "skin_temperature"
        HealthDataType.BASAL_BODY_TEMPERATURE -> "basal_body_temperature"
        HealthDataType.INTERMENSTRUAL_BLEEDING -> "intermenstrual_bleeding"
        HealthDataType.OVULATION_TEST -> "ovulation_test"
        HealthDataType.CERVICAL_MUCUS -> "cervical_mucus"
        HealthDataType.SEXUAL_ACTIVITY -> "sexual_activity"
    }

    /**
     * Whether a stored token is worth spending a call on.
     *
     * A token is refused once it is older than [TOKEN_MAX_AGE_DAYS]; asking anyway costs a round
     * trip to learn what the timestamp already says. A missing token or a missing timestamp both
     * mean "no usable token": the first sync for a type has neither.
     */
    fun isTokenUsable(token: String?, issuedAtMs: Long?, nowMs: Long): Boolean {
        if (token.isNullOrEmpty() || issuedAtMs == null) return false
        if (issuedAtMs > nowMs) return false // clock moved backwards; do not trust the token
        val ageMs = nowMs - issuedAtMs
        return ageMs < TOKEN_MAX_AGE_DAYS * 24 * 60 * 60 * 1000
    }

    /**
     * Deletions from every type in one list, in a stable order (type, then uuid), so two syncs
     * that carry the same deletions produce the same payload and a diff of two payloads is
     * readable.
     */
    fun merge(results: Map<HealthDataType, ChangesResult>): List<DeletedRecord> =
        results.values
            .flatMap { it.deleted }
            .distinctBy { it.type to it.uuid }
            .sortedWith(compareBy({ it.type }, { it.uuid }))

    /**
     * The types whose deletions this sync cannot vouch for, so the payload can name them.
     *
     * Three things land here and mean the same to a receiver: a token that expired, a feed too
     * long to read in one sync, and a type that could not be read at all. In each case the app
     * does not know what was deleted, and a receiver that assumed otherwise would keep records
     * Health Connect no longer has.
     */
    fun expiredTypes(results: Map<HealthDataType, ChangesResult>): List<String> =
        results.filterValues { it.expired || it.error != null }
            .keys
            .map { payloadKey(it) }
            .sorted()

    /** Per payload key, the changes each type's read could not see, see [OutsideWindow]. */
    fun outsideWindow(results: Map<HealthDataType, ChangesResult>): Map<String, OutsideWindow> =
        results.mapNotNull { (type, result) -> result.outsideWindow?.let { payloadKey(type) to it } }
            .toMap()
            .toSortedMap()

    /** Everything [results] hands a payload: deletions, the types that cannot vouch for theirs, and what the read could not see. */
    fun summary(results: Map<HealthDataType, ChangesResult>): DeletionSummary =
        DeletionSummary(merge(results), expiredTypes(results), outsideWindow(results))

    /**
     * The deletions of one read of a type's changes feed that still stand, from its [pages] in
     * the order they came (issues #71 and #72).
     *
     * A source that sets a client record id, as Fitbit does, gets the same record id back
     * when it deletes a record and writes it again: Health Connect derives the id from the
     * package and the client record id. The feed then reports that id as deleted and as
     * written. Within a page the order says nothing, because Health Connect lists a page's
     * writes before its deletions whatever happened first, but a write is reported as the
     * record is when the page is read, so a deleted record yields none. A deletion therefore
     * stands unless the same id was reported written on its own page or a later one. A write
     * on an earlier page means the record was deleted while the feed was being read, and the
     * deletion stands.
     */
    fun standingDeletions(pages: List<FeedPage>): List<String> {
        val lastWritten = mutableMapOf<String, Int>()
        pages.forEachIndexed { index, page -> page.upserted.forEach { lastWritten[it] = index } }
        return pages.flatMapIndexed { index, page ->
            page.deleted.filter { id -> (lastWritten[id] ?: -1) < index }
        }.distinct()
    }

    /**
     * The ids of every record in [data], per payload key, as Health Connect knows them: heart
     * rate and skin temperature samples go out as "record id#epoch millis", and a deletion
     * names the record. Taken from the records read, not the payload, so records that went
     * into a bucket count too.
     */
    fun recordIds(data: HealthData): Map<String, Set<String>> {
        fun ids(list: List<String?>) = list.mapNotNull { it?.substringBefore('#') }.toSet()
        return mapOf(
            HealthDataType.STEPS to ids(data.steps.map { it.uuid }),
            HealthDataType.SLEEP to ids(data.sleep.map { it.uuid }),
            HealthDataType.HEART_RATE to ids(data.heartRate.map { it.uuid }),
            HealthDataType.DISTANCE to ids(data.distance.map { it.uuid }),
            HealthDataType.ACTIVE_CALORIES to ids(data.activeCalories.map { it.uuid }),
            HealthDataType.TOTAL_CALORIES to ids(data.totalCalories.map { it.uuid }),
            HealthDataType.WEIGHT to ids(data.weight.map { it.uuid }),
            HealthDataType.HEIGHT to ids(data.height.map { it.uuid }),
            HealthDataType.BLOOD_PRESSURE to ids(data.bloodPressure.map { it.uuid }),
            HealthDataType.BLOOD_GLUCOSE to ids(data.bloodGlucose.map { it.uuid }),
            HealthDataType.OXYGEN_SATURATION to ids(data.oxygenSaturation.map { it.uuid }),
            HealthDataType.BODY_TEMPERATURE to ids(data.bodyTemperature.map { it.uuid }),
            HealthDataType.RESPIRATORY_RATE to ids(data.respiratoryRate.map { it.uuid }),
            HealthDataType.RESTING_HEART_RATE to ids(data.restingHeartRate.map { it.uuid }),
            HealthDataType.EXERCISE to ids(data.exercise.map { it.uuid }),
            HealthDataType.HYDRATION to ids(data.hydration.map { it.uuid }),
            HealthDataType.NUTRITION to ids(data.nutrition.map { it.uuid }),
            HealthDataType.MINDFULNESS to ids(data.mindfulness.map { it.uuid }),
            HealthDataType.BODY_FAT to ids(data.bodyFat.map { it.uuid }),
            HealthDataType.LEAN_BODY_MASS to ids(data.leanBodyMass.map { it.uuid }),
            HealthDataType.BONE_MASS to ids(data.boneMass.map { it.uuid }),
            HealthDataType.BODY_WATER_MASS to ids(data.bodyWaterMass.map { it.uuid }),
            HealthDataType.HEART_RATE_VARIABILITY to ids(data.hrv.map { it.uuid }),
            HealthDataType.MENSTRUATION_PERIOD to ids(data.menstruationPeriod.map { it.uuid }),
            HealthDataType.MENSTRUATION_FLOW to ids(data.menstruationFlow.map { it.uuid }),
            HealthDataType.BASAL_METABOLIC_RATE to ids(data.basalMetabolicRate.map { it.uuid }),
            HealthDataType.VO2_MAX to ids(data.vo2Max.map { it.uuid }),
            HealthDataType.SKIN_TEMPERATURE to ids(data.skinTemperature.map { it.uuid }),
            HealthDataType.BASAL_BODY_TEMPERATURE to ids(data.basalBodyTemperature.map { it.uuid }),
            HealthDataType.INTERMENSTRUAL_BLEEDING to ids(data.intermenstrualBleeding.map { it.uuid }),
            HealthDataType.OVULATION_TEST to ids(data.ovulationTest.map { it.uuid }),
            HealthDataType.CERVICAL_MUCUS to ids(data.cervicalMucus.map { it.uuid }),
            HealthDataType.SEXUAL_ACTIVITY to ids(data.sexualActivity.map { it.uuid })
        ).filterValues { it.isNotEmpty() }.mapKeys { payloadKey(it.key) }
    }
}
