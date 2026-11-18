package dev.xstreamka.ccsessions.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Пользовательские метаданные сессии — хранятся у нас, а не в транскриптах Claude. */
data class SessionMeta(
    val favorite: Boolean = false,
    val alias: String? = null,
    val tags: List<String> = emptyList(),
    val note: String? = null,
    val archived: Boolean = false,
) {
    val isEmpty get() = !favorite && !archived && alias.isNullOrBlank() && tags.isEmpty() && note.isNullOrBlank()
}

/**
 * Файл meta.json в ~/.claude/cc-sessions — общий для всех IDE (PhpStorm и GoLand видят одно и то же).
 * Каждое изменение — read-modify-write с атомарной заменой файла.
 */
class MetaStore(private val file: Path) {

    @Synchronized
    fun all(): Map<String, SessionMeta> = load()

    fun get(id: String): SessionMeta = all()[id] ?: SessionMeta()

    @Synchronized
    fun update(id: String, f: (SessionMeta) -> SessionMeta): SessionMeta {
        val map = load().toMutableMap()
        val next = f(map[id] ?: SessionMeta())
        if (next.isEmpty) map.remove(id) else map[id] = next
        save(map)
        return next
    }

    private fun load(): Map<String, SessionMeta> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            val sessions = root.obj("sessions") ?: return emptyMap()
            sessions.entrySet().associate { (id, el) ->
                val o = el.asJsonObject
                id to SessionMeta(
                    favorite = o.bool("favorite"),
                    alias = o.str("alias"),
                    tags = o.arr("tags")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList(),
                    note = o.str("note"),
                    archived = o.bool("archived"),
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, SessionMeta>) {
        val sessions = JsonObject()
        map.toSortedMap().forEach { (id, m) ->
            sessions.add(id, JsonObject().apply {
                if (m.favorite) addProperty("favorite", true)
                if (m.archived) addProperty("archived", true)
                m.alias?.takeIf { it.isNotBlank() }?.let { addProperty("alias", it) }
                if (m.tags.isNotEmpty()) add("tags", JsonArray().apply { m.tags.forEach { add(it) } })
                m.note?.takeIf { it.isNotBlank() }?.let { addProperty("note", it) }
            })
        }
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("sessions", sessions)
        }
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, prettyGson.toJson(root))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
