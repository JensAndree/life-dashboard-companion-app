package com.owen282000.lifedashboard.harness

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Evidence for a failed run: what the receiver got, schema errors, whatever a test wants to
 * keep. Written under the app's external files dir, which scripts/instrumented.sh pulls into
 * build/instrumented/witness/ (and CI uploads). [Rule] names the directory after the test.
 */
object Witness {

    @Volatile
    private var current: String = "harness"

    val root: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "instrumented")

    fun save(name: String, content: String) {
        val dir = File(root, current).apply { mkdirs() }
        File(dir, name).writeText(content)
    }

    /** Saves every exchange of [receiver], one file per request, in arrival order. */
    fun saveExchanges(receiver: Receiver) {
        receiver.exchanges.forEachIndexed { index, exchange ->
            val headers = exchange.request.headers.joinToString("\n") { (k, v) -> "$k: $v" }
            val received = java.time.Instant.ofEpochMilli(exchange.receivedAtMs)
            save(
                "request-%02d.txt".format(index + 1),
                "${exchange.request.method} ${exchange.path} -> ${exchange.responseCode} at $received\n$headers\n\n${exchange.text}"
            )
        }
    }

    class Rule(private val receiver: Receiver? = null) : TestWatcher() {
        override fun starting(description: Description) {
            current = "${description.testClass.simpleName}.${description.methodName}"
            File(root, current).deleteRecursively()
        }

        override fun failed(e: Throwable, description: Description) {
            receiver?.let { saveExchanges(it) }
            save("failure.txt", e.stackTraceToString())
        }
    }
}
