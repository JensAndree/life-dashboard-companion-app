package com.owen282000.lifedashboard

/**
 * Readable names for the source apps that most often write to Health Connect, keyed by
 * package name. The app cannot ask Android for another app's label without package
 * visibility it deliberately does not have, so this small table is what makes "Zepp already
 * writes Weight to Health Connect" readable; an unknown package is shown as it is.
 */
object SourceApps {
    private val labels = mapOf(
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.xiaomi.hm.health" to "Zepp",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.withings.wiscale2" to "Withings",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.huawei.health" to "Huawei Health",
        "com.polar.polarflow" to "Polar Flow",
        "com.mc.miband1" to "Notify for Mi Band",
        "nodomain.freeyourgadget.gadgetbridge" to "Gadgetbridge",
        "com.cronometer.android.gold" to "Cronometer",
        "com.myfitnesspal.android" to "MyFitnessPal",
        "com.omronhealthcare.omronconnect" to "OMRON connect",
        "nl.appyhapps.healthsync" to "Health Sync",
        "com.renpho.health" to "Renpho Health",
        "com.eufy.life" to "EufyLife"
    )

    fun label(packageName: String): String = labels[packageName] ?: packageName
}
