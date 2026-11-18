package dev.xstreamka.ccsessions.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Файлы одной сессии Claude Code для удаления «навсегда»: транскрипт и его папка (субагенты, результаты
 * инструментов), session-env, file-history, состояние security-guidance и события плагина.
 * Общие файлы (history.jsonl и т. п.) не трогаем.
 */
object SessionFiles {
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Существующие пути сессии [sessionId]; [transcript] — её .jsonl (из индекса). Для не-UUID id — пусто. */
    fun of(paths: ClaudePaths, sessionId: String, transcript: Path?): List<Path> {
        if (!UUID.matches(sessionId)) return emptyList()
        val root = paths.configDir
        val candidates = buildList {
            if (transcript != null && transcript.fileName.toString() == "$sessionId.jsonl") {
                add(transcript)
                add(transcript.resolveSibling(sessionId))
            }
            add(root.resolve("session-env").resolve(sessionId))
            add(root.resolve("file-history").resolve(sessionId))
            add(paths.eventsDir.resolve("$sessionId.jsonl"))
            val security = root.resolve("security")
            if (Files.isDirectory(security)) {
                Files.list(security).use { s ->
                    s.filter { it.fileName.toString().startsWith("security_warnings_state_$sessionId.") }.forEach { add(it) }
                }
            }
        }
        return candidates.filter { Files.exists(it) && isInside(root, it) }.distinct()
    }

    /** Суммарный размер в байтах (папки — рекурсивно). */
    fun sizeOf(items: List<Path>): Long = items.sumOf { p ->
        if (Files.isDirectory(p)) Files.walk(p).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        else Files.size(p)
    }

    /** Удаляет [items] (папки — рекурсивно). Всё, что вне каталога Claude, — IllegalArgumentException, ничего не удаляется. */
    fun delete(paths: ClaudePaths, items: List<Path>) {
        items.forEach { require(isInside(paths.configDir, it)) { I18n.msg("error.pathOutsideClaude", it.toString()) } }
        for (p in items) {
            if (!Files.exists(p)) continue
            if (Files.isDirectory(p)) {
                Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            } else {
                Files.deleteIfExists(p)
            }
        }
    }

    private fun isInside(root: Path, p: Path): Boolean {
        val r = root.toAbsolutePath().normalize()
        val n = p.toAbsolutePath().normalize()
        return n != r && n.startsWith(r)
    }
}
