package io.github.hafeee44lab.solargrid.data.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Parses a reply body as a JSON object, or null when it is HTML, empty or anything else. */
internal fun jsonObject(body: String): JsonObject? = try {
    JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
} catch (e: Exception) {
    null
}

internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }

internal fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

/** The first of [keys] that holds a number (or a numeric string). */
internal fun JsonObject.num(vararg keys: String): Double? {
    for (key in keys) {
        val element = get(key) ?: continue
        if (!element.isJsonPrimitive) continue
        val primitive = element.asJsonPrimitive
        val value = if (primitive.isNumber) primitive.asDouble.takeIf { it.isFinite() } else parseNumber(primitive.asString)
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.int(key: String): Int? = num(key)?.toInt()
