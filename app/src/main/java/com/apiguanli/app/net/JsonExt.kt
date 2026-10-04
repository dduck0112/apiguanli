package com.apiguanli.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Lenient accessors: NewAPI forks differ in field types (numbers as strings, nulls, etc.).

private fun JsonObject.prim(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonObject.str(key: String): String? = prim(key)?.content

fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.dbl(key: String): Double? = prim(key)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

fun JsonObject.bool(key: String): Boolean? = prim(key)?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

fun JsonObject.strList(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }.orEmpty()
