package dev.xstreamka.ccsessions.core

import com.google.gson.JsonObject
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class SessionSummary(
    val id: String,
    val file: Path? = null,
    val cwd: String? = null,
    val gitBranch: String? = null,
    val version: String? = null,
    val entrypoint: String? = null,
    val customTitle: String? = null,
    val aiTitle: String? = null,
    val summaryTitle: String? = null,
    val firstPrompt: String? = null,
    val lastPrompt: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val prompts: Int = 0,
    val assistantMessages: Int = 0,
    val toolCalls: Int = 0,
    val model: String? = null,
    val usage: Usage = Usage(),
    val lastContext: Long = 0,
    val sizeBytes: Long = 0,
    val forkedFrom: String? = null,
) {
    /** Air архивирует сессию, дописывая в транскрипт custom-title с префиксом «[archived] ». */
    val archivedInAir: Boolean get() = customTitle?.startsWith(AIR_ARCHIVED_PREFIX) == true

    /** Название для записи в транскрипт (архив Air): без префикса архива и без обрезки, первый промпт — одной строкой. */
    val baseTitle: String
        get() = listOf(customTitle?.removePrefix(AIR_ARCHIVED_PREFIX), aiTitle, summaryTitle)
            .firstOrNull { !it.isNullOrBlank() }
            ?: firstPrompt?.takeIf { it.isNotBlank() }?.let { oneLine(it, 120) }
            ?: emptyTitle()

    /** Заголовок по приоритету: имя /rename (без префикса архива Air) → AI-заголовок → summary → первый промпт. */
    val title: String
        get() = listOf(customTitle?.removePrefix(AIR_ARCHIVED_PREFIX), aiTitle, summaryTitle, firstPrompt)
            .firstOrNull { !it.isNullOrBlank() }
            ?.let { oneLine(it, 120) }
            ?: emptyTitle()

    private fun emptyTitle() = I18n.msg("session.empty", id.take(8))

    val isEmpty: Boolean get() = prompts == 0 && assistantMessages == 0
    val isSdk: Boolean get() = entrypoint?.startsWith("sdk") == true

    companion object {
        const val AIR_ARCHIVED_PREFIX = "[archived] "

        fun oneLine(s: String, max: Int): String {
            val t = s.replace(Regex("\\s+"), " ").trim()
            return if (t.length <= max) t else t.substring(0, max - 1) + "…"
        }
    }
}

/** Потоковый накопитель сводки по строкам транскрипта. Поддерживает дочитывание. */
class SessionAccumulator(private val sessionId: String) {
    private var cwd: String? = null
    private var gitBranch: String? = null
    private var version: String? = null
    private var entrypoint: String? = null
    private var customTitle: String? = null
    private var aiTitle: String? = null
    private var summaryTitle: String? = null
    private var firstPrompt: String? = null
    private var lastPrompt: String? = null
    private var createdAt: Instant? = null
    private var updatedAt: Instant? = null
    private var prompts = 0
    private var toolCalls = 0
    private var model: String? = null
    private var lastContext = 0L
    private var forkedFrom: String? = null
    private val usageByMessage = HashMap<String, Usage>()
    private val seen = HashSet<String>()

    fun accept(o: JsonObject) {
        when (o.str("type")) {
            "custom-title" -> o.str("customTitle")?.let { customTitle = it }
            "ai-title" -> o.str("aiTitle")?.let { aiTitle = it }
            "summary" -> o.str("summary")?.let { summaryTitle = it }
            "agent-name" -> if (customTitle == null) o.str("agentName")?.let { customTitle = it }
            "last-prompt" -> o.str("lastPrompt")?.let { lastPrompt = it }
        }
        val uuid = o.str("uuid")
        if (uuid != null && !seen.add(uuid)) return
        if (o.bool("isSidechain")) return

        o.str("cwd")?.let { cwd = it }
        o.str("gitBranch")?.takeIf { it.isNotBlank() }?.let { gitBranch = it }
        o.str("version")?.let { version = it }
        if (entrypoint == null) o.str("entrypoint")?.let { entrypoint = it }
        if (forkedFrom == null) o.obj("forkedFrom")?.let { forkedFrom = it.str("sessionId") }
        Lines.timestamp(o)?.let { ts ->
            if (createdAt == null || ts < createdAt) createdAt = ts
            if (updatedAt == null || ts > updatedAt) updatedAt = ts
        }

        when (o.str("type")) {
            "user" -> {
                if (o.bool("isMeta") || o.bool("isCompactSummary")) return
                val (text, images) = Lines.userText(o.obj("message")) ?: return
                when (val c = Lines.classifyUserText(text, images)) {
                    is Lines.Prompt -> {
                        prompts++
                        val t = c.text.ifBlank { I18n.msg("content.image") }
                        if (firstPrompt == null) firstPrompt = t
                        lastPrompt = t
                    }
                    is Lines.Command -> if (firstPrompt == null && c.name !in setOf("/clear", "/resume", "/exit")) {
                        // команды вроде /gsd:plan … — тоже хороший заголовок
                        firstPrompt = (c.name + " " + c.args).trim()
                    }
                    else -> {}
                }
            }
            "assistant" -> {
                val msg = o.obj("message") ?: return
                msg.str("model")?.takeIf { it != "<synthetic>" }?.let { model = it }
                val usage = Usage.from(msg.obj("usage"))
                val mid = msg.str("id") ?: uuid ?: return
                if (usage != null) {
                    usageByMessage[mid] = usage // стриминг пишет несколько строк на одно сообщение — берём последнюю
                    if (usage.context > 0) lastContext = usage.context
                }
                msg.arr("content")?.forEach { b ->
                    if (b.isJsonObject && b.asJsonObject.str("type") == "tool_use") toolCalls++
                }
            }
        }
    }

    fun build(sizeBytes: Long, file: Path? = null) = SessionSummary(
        id = sessionId,
        file = file,
        cwd = cwd,
        gitBranch = gitBranch,
        version = version,
        entrypoint = entrypoint,
        customTitle = customTitle,
        aiTitle = aiTitle,
        summaryTitle = summaryTitle,
        firstPrompt = firstPrompt,
        lastPrompt = lastPrompt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        prompts = prompts,
        assistantMessages = usageByMessage.size,
        toolCalls = toolCalls,
        model = model,
        usage = usageByMessage.values.fold(Usage()) { a, b -> a + b },
        lastContext = lastContext,
        sizeBytes = sizeBytes,
        forkedFrom = forkedFrom,
    )
}

/**
 * Индекс сессий с инкрементальным дочитыванием: транскрипты append-only,
 * поэтому при росте файла читаем только новый хвост.
 */
class SessionIndex {
    private class Entry(val acc: SessionAccumulator, var offset: Long, var mtime: Long, var summary: SessionSummary)

    private val cache = ConcurrentHashMap<Path, Entry>()

    /**
     * Забыть все сводки: следующий [scan] перечитает транскрипты целиком. Нужно при смене языка плагина —
     * в сводках лежат тексты, подставленные при разборе (например «[изображение]» вместо пустого промпта).
     */
    fun clear() = cache.clear()

    /** Список сессий в каталогах проектов (без субагентов), отсортированный по последней активности. */
    fun scan(projectDirs: Collection<Path>): List<SessionSummary> {
        val alive = HashSet<Path>()
        val result = ArrayList<SessionSummary>()
        for (dir in projectDirs) {
            if (!Files.isDirectory(dir)) continue
            val files = Files.list(dir).use { s ->
                s.filter { isMainTranscript(it) }.toList()
            }
            for (f in files) {
                alive.add(f)
                summarize(f)?.let { if (!it.isEmpty) result += it }
            }
        }
        cache.keys.removeIf { it.parent in projectDirs && it !in alive }
        return result.sortedByDescending { it.updatedAt ?: Instant.EPOCH }
    }

    fun summarize(file: Path): SessionSummary? {
        val size = try { Files.size(file) } catch (_: Exception) { return null }
        val mtime = try { Files.getLastModifiedTime(file).toMillis() } catch (_: Exception) { 0L }
        val id = file.fileName.toString().removeSuffix(".jsonl")
        var e = cache[file]
        if (e != null && e.offset == size && e.mtime == mtime) return e.summary
        if (e == null || size < e.offset) {
            e = Entry(SessionAccumulator(id), 0, mtime, SessionSummary(id, file))
            cache[file] = e
        }
        val newOffset = readLinesFrom(file, e.offset) { e.acc.accept(it) }
        e.offset = newOffset
        e.mtime = mtime
        e.summary = e.acc.build(size, file)
        return e.summary
    }

    companion object {
        fun isMainTranscript(p: Path): Boolean {
            val n = p.fileName.toString()
            return n.endsWith(".jsonl") && !n.startsWith("agent-") && Files.isRegularFile(p)
        }

        /**
         * Читает полные строки начиная с [offset]; незавершённую последнюю строку
         * (файл дописывается прямо сейчас) оставляет на следующий раз. Возвращает новый offset.
         */
        fun readLinesFrom(file: Path, offset: Long, onObject: (JsonObject) -> Unit): Long =
            readRawLinesFrom(file, offset) { line -> parseObject(line)?.let(onObject) }

        /** То же, что [readLinesFrom], но отдаёт сырые строки. Читает кусками по 1 МБ. */
        fun readRawLinesFrom(file: Path, offset: Long, onLine: (String) -> Unit): Long {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val len = raf.length()
                if (offset >= len) return offset
                raf.seek(offset)
                val chunk = ByteArray(1 shl 20)
                val pending = java.io.ByteArrayOutputStream()
                var pos = offset
                var consumedTo = offset
                while (pos < len) {
                    val n = raf.read(chunk, 0, minOf(chunk.size.toLong(), len - pos).toInt())
                    if (n <= 0) break
                    var start = 0
                    for (i in 0 until n) {
                        if (chunk[i] == '\n'.code.toByte()) {
                            pending.write(chunk, start, i - start)
                            if (pending.size() > 0) onLine(pending.toString(StandardCharsets.UTF_8))
                            pending.reset()
                            start = i + 1
                            consumedTo = pos + i + 1
                        }
                    }
                    pending.write(chunk, start, n - start)
                    pos += n
                }
                // хвост без \n: принимаем, только если это законченный JSON
                if (pending.size() > 0) {
                    val tail = pending.toString(StandardCharsets.UTF_8)
                    if (parseObject(tail) != null) {
                        onLine(tail)
                        consumedTo = pos
                    }
                }
                return consumedTo
            }
        }

        /** Полнотекстовый поиск по сырому файлу (UTF-8, без учёта регистра). */
        fun fileContains(file: Path, query: String): Boolean {
            if (query.isBlank()) return true
            val q = query.lowercase()
            return try {
                Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
                    lines.any { it.lowercase().contains(q) }
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
