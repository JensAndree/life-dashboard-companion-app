package com.owen282000.lifedashboard.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Literal-key access to received JSON, so the tests never read a payload through the app's own models. */
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.num(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content

fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
