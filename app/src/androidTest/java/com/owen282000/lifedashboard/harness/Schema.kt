package com.owen282000.lifedashboard.harness

import androidx.test.platform.app.InstrumentationRegistry
import io.github.optimumcode.json.schema.JsonSchema
import kotlinx.serialization.json.Json

/**
 * docs/webhook-schema.json, copied into the test APK's assets by the build (see
 * app/build.gradle.kts), so the documented schema is the only one. Draft-07; `format` is an
 * assertion there, which matters for the dozens of date-time fields.
 */
object Schema {

    private val schema: JsonSchema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("webhook-schema.json").bufferedReader().use { it.readText() }
        JsonSchema.fromDefinition(text)
    }

    /** The validation errors for [body], empty when it is valid. Also kept as a witness when not. */
    fun errors(body: String, name: String = "payload"): List<String> {
        val errors = mutableListOf<String>()
        schema.validate(Json.parseToJsonElement(body)) { errors += "${it.objectPath}: ${it.message}" }
        if (errors.isNotEmpty()) Witness.save("schema-errors-$name.txt", errors.joinToString("\n") + "\n\n" + body)
        return errors
    }
}
