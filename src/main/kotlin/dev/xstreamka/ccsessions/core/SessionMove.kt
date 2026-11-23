package dev.xstreamka.ccsessions.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Перенос сессии в другой проект. Claude Code ищет сессию для `--resume` в каталоге
 * `~/.claude/projects/<закодированный путь проекта>`, а плагин запускает её в `cwd` из транскрипта, поэтому:
 * транскрипт, его папка (субагенты, результаты инструментов) и соседние файлы `<id>.*` переезжают в каталог
 * нового проекта, а в строках транскриптов `cwd` старого проекта заменяется на новый (вложенные пути — с тем же хвостом).
 * Остальной текст транскрипта (сообщения, вывод инструментов) не меняется.
 */
object SessionMove {
    private val CWD = Regex("\"cwd\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    class MoveException(message: String) : Exception(message)

    /**
     * Каталог транскриптов для проекта [workDir]: существующий или новый. Слишком длинные пути Claude Code
     * обрезает и дописывает хеш — такой каталог, если его ещё нет, создать не можем: null.
     */
    fun targetDir(paths: ClaudePaths, workDir: String): Path? {
        paths.findProjectDirs(workDir).firstOrNull()?.let { return it }
        val encoded = ClaudePaths.encodeProjectDir(workDir)
        return if (encoded.length > 200) null else paths.projectDirFor(workDir)
    }

    /**
     * Корень проекта, в котором сессия начата: тот `cwd` из транскрипта, чей закодированный путь совпадает
     * с именем каталога транскрипта; если такого нет — первый встреченный `cwd`.
     */
    fun sourceRoot(transcript: Path): String? {
        val dirName = transcript.parent?.fileName?.toString()
        var first: String? = null
        var found: String? = null
        SessionIndex.readRawLinesFrom(transcript, 0) { line ->
            if (found != null) return@readRawLinesFrom
            for (m in CWD.findAll(line)) {
                val cwd = unescape(m.groupValues[1]) ?: continue
                if (first == null) first = cwd
                if (ClaudePaths.encodeProjectDir(cwd) == dirName) {
                    found = cwd
                    break
                }
            }
        }
        return found ?: first
    }

    /**
     * Папка проекта, которой принадлежит каталог транскриптов [dir]: по `cwd` в начале свежих транскриптов
     * (имя каталога — закодированный путь, обратно его не раскодировать). null — не нашли.
     */
    fun projectRootOf(dir: Path): String? {
        val name = dir.fileName?.toString() ?: return null
        val files = Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }.toList()
        }.sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
        for (f in files.take(3)) {
            try {
                Files.newBufferedReader(f, StandardCharsets.UTF_8).use { r ->
                    var n = 0
                    while (n++ < 200) {
                        val line = r.readLine() ?: break
                        for (m in CWD.findAll(line)) {
                            val cwd = unescape(m.groupValues[1]) ?: continue
                            if (ClaudePaths.encodeProjectDir(cwd) == name) return cwd
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Строка транскрипта с `cwd` из [from] (или вложенным в него) — переписанная на [to]; остальное без изменений. */
    fun rewriteLine(line: String, from: String, to: String): String {
        if (!line.contains("\"cwd\":\"")) return line
        val fromRoot = from.trimEnd('/', '\\')
        val toRoot = to.trimEnd('/', '\\')
        return CWD.replace(line) { m ->
            val cwd = unescape(m.groupValues[1]) ?: return@replace m.value
            val rest = when {
                cwd == fromRoot || cwd == "$fromRoot/" || cwd == "$fromRoot\\" -> ""
                cwd.startsWith("$fromRoot/") || cwd.startsWith("$fromRoot\\") -> cwd.substring(fromRoot.length)
                else -> return@replace m.value
            }
            "\"cwd\":" + gson.toJson(toRoot + rest)
        }
    }

    /**
     * Переносит сессию [sessionId] (транскрипт [transcript]) из проекта [from] в проект [to].
     * Возвращает новый путь транскрипта. При ошибке исходные файлы остаются на месте.
     */
    fun move(paths: ClaudePaths, sessionId: String, transcript: Path, from: String, to: String): Path {
        if (!Files.isRegularFile(transcript)) throw MoveException(I18n.msg("error.move.noTranscript"))
        val sourceDir = transcript.parent
        val targetDir = targetDir(paths, to) ?: throw MoveException(I18n.msg("error.move.pathTooLong", to))
        if (targetDir.toAbsolutePath().normalize() == sourceDir.toAbsolutePath().normalize()) {
            throw MoveException(I18n.msg("error.move.sameProject"))
        }
        val name = transcript.fileName.toString()
        val companions = Files.list(sourceDir).use { s ->
            s.filter { it.fileName.toString().let { n -> n != name && (n == sessionId || n.startsWith("$sessionId.")) } }.toList()
        }
        for (p in listOf(transcript) + companions) {
            if (Files.exists(targetDir.resolve(p.fileName))) throw MoveException(I18n.msg("error.move.exists", targetDir.resolve(p.fileName).toString()))
        }
        Files.createDirectories(targetDir)

        // Сначала новый транскрипт рядом с целевым местом, потом — атомарная подмена; старый удаляем последним
        val target = targetDir.resolve(name)
        val tmp = targetDir.resolve(".$name.moving")
        try {
            rewriteFile(transcript, tmp, from, to)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        for (c in companions) {
            val dest = targetDir.resolve(c.fileName)
            Files.move(c, dest)
            if (Files.isDirectory(dest)) {
                Files.walk(dest).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }.toList() }
                    .forEach { f -> rewriteInPlace(f, from, to) }
            }
        }
        Files.delete(transcript)
        return target
    }

    private fun rewriteInPlace(file: Path, from: String, to: String) {
        val tmp = file.resolveSibling(".${file.fileName}.moving")
        try {
            rewriteFile(file, tmp, from, to)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    /** Переписывает построчно, сохраняя разделители строк и отсутствие перевода строки в конце. */
    private fun rewriteFile(source: Path, dest: Path, from: String, to: String) {
        val text = String(Files.readAllBytes(source), StandardCharsets.UTF_8)
        val out = StringBuilder(text.length + 1024)
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            out.append(rewriteLine(text.substring(start, end), from, to))
            if (nl >= 0) out.append('\n')
            start = end + 1
        }
        Files.write(dest, out.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun unescape(escaped: String): String? =
        runCatching { JsonParser.parseString("\"$escaped\"").asString }.getOrNull()
}
