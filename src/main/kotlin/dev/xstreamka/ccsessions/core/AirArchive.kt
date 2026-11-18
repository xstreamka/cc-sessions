package dev.xstreamka.ccsessions.core

import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Архив в формате JetBrains Air: Air архивирует сессию, дописывая в её транскрипт пару
 * `custom-title` + `agent-name` с префиксом «[archived] », и возвращает из архива той же парой без префикса.
 * Пишем так же, чтобы архив был общий для плагина и Air. Та же пара строк задаёт и имя сессии
 * (как /rename в Claude Code) — по нему работает `claude --resume <имя>`.
 */
object AirArchive {

    /** Две строки JSONL (каждая с переводом строки) для записи названия с пометкой архива или без неё. */
    fun titleLines(sessionId: String, title: String, archived: Boolean): String {
        val full = (if (archived) SessionSummary.AIR_ARCHIVED_PREFIX else "") + title
        val customTitle = JsonObject().apply {
            addProperty("type", "custom-title")
            addProperty("customTitle", full)
            addProperty("sessionId", sessionId)
        }
        val agentName = JsonObject().apply {
            addProperty("type", "agent-name")
            addProperty("agentName", full)
            addProperty("sessionId", sessionId)
        }
        return compactGson.toJson(customTitle) + "\n" + compactGson.toJson(agentName) + "\n"
    }

    /** Дописывает пару строк в конец транскрипта одним вызовом записи; если файл не кончается переводом строки — добавляет его. */
    fun write(transcript: Path, sessionId: String, title: String, archived: Boolean) {
        val needsNewline = Files.size(transcript) > 0 && lastByte(transcript) != '\n'.code.toByte()
        val text = (if (needsNewline) "\n" else "") + titleLines(sessionId, title, archived)
        Files.write(transcript, text.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
    }

    private fun lastByte(file: Path): Byte = Files.newByteChannel(file).use { ch ->
        val buf = java.nio.ByteBuffer.allocate(1)
        ch.position(ch.size() - 1)
        ch.read(buf)
        buf.get(0)
    }
}
