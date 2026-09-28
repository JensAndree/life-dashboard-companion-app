package com.owen282000.lifedashboard.harness

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import com.owen282000.lifedashboard.HealthConnectManager
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/** The Health Connect calls the wrappers below can count or hold up. */
enum class HcCall { READ_RECORDS, AGGREGATE, AGGREGATE_GROUPED, GET_CHANGES_TOKEN, GET_CHANGES, INSERT_RECORDS, DELETE_RECORDS, GRANTED_PERMISSIONS }

/**
 * Every Health Connect call the suite makes, the app's and the harness's own, per kind.
 *
 * Health Connect limits read calls per app: by default 2000 per 15 minutes and 16000 per 24 hours
 * in the foreground, half that in the background, where every readRecords page, aggregate call,
 * getChanges page and getChangesToken costs one (RateLimiter in the platform module; the numbers
 * are flags a device can change). The tests share that budget with each other. [AppStateRule] logs the
 * count after every test, so a test that starts reading far more than it should shows up in
 * the log long before the suite runs into the limit.
 */
object HcCalls {
    private val counts = ConcurrentHashMap<HcCall, AtomicInteger>()
    private val readsByType = ConcurrentHashMap<String, AtomicInteger>()

    fun record(call: HcCall) {
        counts.getOrPut(call) { AtomicInteger() }.incrementAndGet()
    }

    /** One readRecords page of [recordType], so a test can see which types a sync read and how often. */
    fun recordRead(recordType: String) {
        readsByType.getOrPut(recordType) { AtomicInteger() }.incrementAndGet()
    }

    /** readRecords pages per record type (the class's simple name) since the last [reset]. */
    fun readsByType(): Map<String, Int> = readsByType.mapValues { it.value.get() }

    fun snapshot(): Map<HcCall, Int> = counts.mapValues { it.value.get() }.filterValues { it > 0 }

    fun reset() {
        counts.clear()
        readsByType.clear()
    }

    fun total(): Int = counts.values.sumOf { it.get() }
}

/** A client that counts each call in [HcCalls] and hands it to the real one. */
open class CountingHealthConnectClient(private val real: HealthConnectClient) : HealthConnectClient by real {

    protected open suspend fun before(call: HcCall) = HcCalls.record(call)

    override val permissionController: PermissionController
        get() = object : PermissionController by real.permissionController {
            override suspend fun getGrantedPermissions(): Set<String> {
                before(HcCall.GRANTED_PERMISSIONS)
                return real.permissionController.getGrantedPermissions()
            }
        }

    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
        HcCalls.recordRead(request.recordType.simpleName.orEmpty())
        before(HcCall.READ_RECORDS)
        return real.readRecords(request)
    }

    override suspend fun aggregate(request: AggregateRequest): AggregationResult {
        before(HcCall.AGGREGATE)
        return real.aggregate(request)
    }

    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> {
        before(HcCall.AGGREGATE_GROUPED)
        return real.aggregateGroupByPeriod(request)
    }

    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> {
        before(HcCall.AGGREGATE_GROUPED)
        return real.aggregateGroupByDuration(request)
    }

    override suspend fun getChangesToken(request: ChangesTokenRequest): String {
        before(HcCall.GET_CHANGES_TOKEN)
        return real.getChangesToken(request)
    }

    override suspend fun getChanges(changesToken: String): ChangesResponse {
        before(HcCall.GET_CHANGES)
        return real.getChanges(changesToken)
    }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        before(HcCall.INSERT_RECORDS)
        return real.insertRecords(records)
    }

    override suspend fun deleteRecords(recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>) {
        before(HcCall.DELETE_RECORDS)
        real.deleteRecords(recordType, recordIdsList, clientRecordIdsList)
    }

    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
        before(HcCall.DELETE_RECORDS)
        real.deleteRecords(recordType, timeRangeFilter)
    }
}

/**
 * A counting client that holds up the calls named in [held]: for [delayMs] each, or until the
 * caller is cancelled when [delayMs] is null. Health Connect runs in system_server, so neither
 * force-stopping its controller nor Doze makes it slow (report 02 of P2-4); this is how the
 * budgets of the sync are tested. [held] may change while a sync runs.
 */
class SlowHealthConnectClient(
    real: HealthConnectClient,
    private val delayMs: Long? = null
) : CountingHealthConnectClient(real) {

    val held: MutableSet<HcCall> = ConcurrentHashMap.newKeySet()

    override suspend fun before(call: HcCall) {
        super.before(call)
        if (call in held) {
            if (delayMs == null) awaitCancellation() else delay(delayMs)
        }
    }
}

/** The app's own managers, built on a client of the test's choice. */
object Managers {
    fun counting(context: android.content.Context): HealthConnectManager =
        HealthConnectManager(context) { CountingHealthConnectClient(HealthConnectClient.getOrCreate(it)) }
}
