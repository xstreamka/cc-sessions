package dev.xstreamka.ccsessions.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

internal val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
internal val compactGson = GsonBuilder().disableHtmlEscaping().create()

internal fun parseObject(line: String): JsonObject? = try {
    val el = JsonParser.parseString(line)
    if (el.isJsonObject) el.asJsonObject else null
} catch (_: Exception) {
    null
}

internal fun JsonObject.str(key: String): String? {
    val el = get(key) ?: return null
    return if (el.isJsonPrimitive) el.asString else null
}

internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
internal fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

internal fun JsonObject.long(key: String): Long? {
    val el = get(key) ?: return null
    if (!el.isJsonPrimitive) return null
    val p = el.asJsonPrimitive
    return when {
        p.isNumber -> p.asLong
        p.isString -> p.asString.toLongOrNull()
        else -> null
    }
}

/** Текстовое представление произвольного JSON-контента (для tool_result). */
internal fun JsonElement?.flattenText(limit: Int = Int.MAX_VALUE): String {
    if (this == null || isJsonNull) return ""
    val sb = StringBuilder()
    fun walk(e: JsonElement) {
        if (sb.length >= limit) return
        when {
            e.isJsonPrimitive -> sb.append(e.asString)
            e.isJsonArray -> e.asJsonArray.forEachIndexed { i, x ->
                if (i > 0 && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                walk(x)
            }
            e.isJsonObject -> {
                val o = e.asJsonObject
                when (o.str("type")) {
                    "text" -> sb.append(o.str("text") ?: "")
                    "image" -> sb.append(I18n.msg("content.image"))
                    "tool_reference" -> sb.append("[").append(o.str("tool_name") ?: "tool").append("]")
                    else -> sb.append(compactGson.toJson(o))
                }
            }
        }
    }
    walk(this)
    return if (sb.length > limit) sb.substring(0, limit) else sb.toString()
}

internal fun jsonString(s: String): String = compactGson.toJson(JsonPrimitive(s))
