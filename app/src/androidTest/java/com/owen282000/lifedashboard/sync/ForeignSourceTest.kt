package com.owen282000.lifedashboard.sync

import android.os.ParcelFileDescriptor
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.FakeIntegration
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The app next to another Health Connect app, the :hc-fixture APK, which writes records with
 * client record ids the way a scale or a watch does. The echo filter must tell those apart
 * from what Receive wrote under the app's own name (e1f6ebe), and the warning about other
 * apps writing a type must see the fixture.
 */
@RunWith(AndroidJUnit4::class)
class ForeignSourceTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val integration = FakeIntegration(TestSetup.HEALTH_SECRET)

    /** Runs one class of the fixture's own test APK through the shell and returns its output. */
    private fun fixtureInstrumentation(testClass: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am instrument -w --no-hidden-api-checks -e class $FIXTURE.$testClass $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner"
        ).let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }

    /**
     * Foreign records go out, each exactly once and named after the fixture; the weight
     * Receive wrote does not, although it shares the type and carries a client record id too;
     * otherSourcesWriting names the fixture and nothing else.
     */
    @Test
    fun foreignRecordsGoOutOwnReceiveWritesDoNot() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, WEIGHT), receive = setOf(WriteBackType.WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class)
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        integration.offer(FakeIntegration.weight("sensor.scale_weight@9", 80.4, ago(15)))
        val seeded = fixtureInstrumentation("SeedForSuite")
        assertTrue("the fixture seeded: $seeded", "OK (1 test)" in seeded)
        try {
            val foreign = fixture.read(WeightRecord::class).filter { it.metadata.dataOrigin.packageName == FIXTURE } +
                fixture.read(StepsRecord::class).filter { it.metadata.dataOrigin.packageName == FIXTURE }
            assertEquals(4, foreign.size)

            TestSetup.syncManager().performSync().getOrThrow() // sends the fixture's records, writes the received weight
            assertEquals(1, fixture.read(WeightRecord::class).count { it.metadata.dataOrigin.packageName == context.packageName })
            assertEquals("nothing again, and not the received weight", HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())

            val delivered = receiver.exchanges.map { Conservation.parse(it.text) }
            Conservation.assertExactlyOnce(foreign.map { it.metadata.id }.toSet(), delivered)
            assertTrue("named after the fixture", delivered.flatMap { Conservation.records(it) }.all { it.third == FIXTURE })
            assertEquals(listOf(FIXTURE), HealthConnectManager(context).otherSourcesWriting(WriteBackType.WEIGHT))
        } finally {
            val cleared = fixtureInstrumentation("ClearFixtureData")
            assertTrue("the fixture cleared its records: $cleared", "OK (1 test)" in cleared)
        }
    }

    companion object {
        const val FIXTURE = "com.owen282000.lifedashboard.fixture"
    }
}
