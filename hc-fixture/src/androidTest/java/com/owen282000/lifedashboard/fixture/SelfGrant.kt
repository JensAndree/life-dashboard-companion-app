package com.owen282000.lifedashboard.fixture

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import java.util.regex.Pattern

/**
 * Grants the fixture every Health Connect permission its manifest declares, the way the app's
 * suite grants its own (app/src/androidTest, HealthPermissionRule): `grantHealthPermission`
 * under the shell's MANAGE_HEALTH_PERMISSIONS, which also puts the fixture in Health Connect's
 * priority list, so its records count in aggregates. That method is hidden: run the
 * instrumentation with `--no-hidden-api-checks`. When it is not available, the permission
 * dialog is accepted with UiAutomator, by resource id, never by text.
 *
 * Each APK can only grant itself (Health Connect refuses packages the caller cannot see), so
 * this small piece exists twice, here and in the app's suite.
 */
object SelfGrant {

    private const val TAG = "LdFixture"
    private val ALLOW_ALL = Pattern.compile(".*:id/settingslib_main_switch_bar")
    private val SWITCH = Pattern.compile(".*:id/switchWidget")
    private val ALLOW = Pattern.compile(".*:id/primary_button_outline")
    private val ONBOARDING = Pattern.compile(".*:id/onboarding")
    private val GET_STARTED = Pattern.compile(".*:id/primary_button_full")

    fun ensure(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wanted = declared(context)
        if ((wanted - granted(context)).isEmpty()) return "already"
        val viaShell = runCatching { viaShellIdentity(context, wanted - granted(context)) }
            .onFailure { Log.w(TAG, "grantHealthPermission failed, falling back to the dialog", it) }
            .isSuccess && (wanted - granted(context)).isEmpty()
        val route = if (viaShell) "shell" else {
            viaDialog(context, wanted - granted(context))
            "dialog"
        }
        val missing = wanted - granted(context)
        check(missing.isEmpty()) { "Health Connect permissions still missing after the $route route: $missing" }
        return route
    }

    private fun declared(context: Context): Set<String> =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }.toSet()

    private fun granted(context: Context): Set<String> = runBlocking {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    }

    private fun viaShellIdentity(context: Context, permissions: Set<String>) {
        val manager = context.getSystemService(android.health.connect.HealthConnectManager::class.java)
        val grant = manager.javaClass.getMethod("grantHealthPermission", String::class.java, String::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_HEALTH_PERMISSIONS")
        try {
            permissions.forEach { grant.invoke(manager, context.packageName, it) }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun viaDialog(context: Context, permissions: Set<String>) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(FixtureActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestPermissions(permissions.toTypedArray(), 4714) }
            repeat(6) {
                if ((permissions - granted(context)).isEmpty()) return
                // A device where Health Connect was never opened shows its onboarding first.
                if (device.wait(Until.findObject(By.res(ONBOARDING)), 3_000) != null) {
                    device.findObject(By.res(GET_STARTED))?.click()
                    device.waitForIdle()
                }
                device.wait(Until.findObject(By.res(ALLOW)), 10_000) ?: return@repeat
                // The data screen has an "Allow all" switch; the screen for background and history
                // access has one switch per permission and nothing to switch them all.
                val allowAll = device.findObject(By.res(ALLOW_ALL))
                if (allowAll != null) {
                    val toggle = allowAll.findObject(By.res(SWITCH)) ?: allowAll
                    if (!toggle.isChecked) toggle.click()
                } else {
                    device.findObjects(By.res(SWITCH)).filter { !it.isChecked }.forEach { it.click() }
                }
                device.wait(Until.findObject(By.res(ALLOW).enabled(true)), 5_000)?.click()
                device.waitForIdle()
            }
        }
    }
}
