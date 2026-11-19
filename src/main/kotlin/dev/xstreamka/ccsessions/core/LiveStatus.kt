package dev.xstreamka.ccsessions.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Событие, записанное cc-sessions-hook. */
data class LiveEvent(
    val ts: Long,
    val event: String,
    val sessionId: String,
    val cwd: String? = null,
    val transcriptPath: String? = null,
    val permissionMode: String? = null,
    val agentId: String? = null,
    val agentType: String? = null,
    val toolName: String? = null,
    val toolSummary: String? = null,
    val prompt: String? = null,
    val message: String? = null,
    val kind: String? = null,
    val lastMessage: String? = null,
    val error: String? = null,
) {
    companion object {
        fun from(o: JsonObject): LiveEvent? {
            val event = o.str("event") ?: return null
            val sid = o.str("session_id") ?: return null
            return LiveEvent(
                ts = o.long("ts") ?: return null,
                event = event,
                sessionId = sid,
                cwd = o.str("cwd"),
                transcriptPath = o.str("transcript_path"),
                permissionMode = o.str("permission_mode"),
                agentId = o.str("agent_id"),
                agentType = o.str("agent_type"),
                toolName = o.str("tool_name"),
                toolSummary = o.str("tool_summary"),
                prompt = o.str("prompt"),
                message = o.str("message"),
                kind = o.str("kind"),
                lastMessage = o.str("last_message"),
                error = o.str("error"),
            )
        }
    }
}

enum class LiveStatus(private val key: String, val active: Boolean) {
    WORKING("live.status.working", true),
    TOOL("live.status.tool", true),
    COMPACTING("live.status.compacting", true),
    WAITING_PERMISSION("live.status.waitingPermission", true),
    /** Основной ход закончился, но запущенные им фоновые агенты ещё работают — по их итогам Claude продолжит сам. */
    BACKGROUND("live.status.background", true),
    IDLE("live.status.idle", false),
    ERROR("live.status.error", false),
    ENDED("live.status.ended", false),
    STALE("live.status.stale", false);

    /** Подпись статуса в текущей локали. */
    val label: String get() = I18n.msg(key)
}

data class LiveState(
    val sessionId: String,
    val status: LiveStatus,
    val since: Long,
    val updatedAt: Long,
    val cwd: String? = null,
    val transcriptPath: String? = null,
    val permissionMode: String? = null,
    val tool: String? = null,
    val toolSummary: String? = null,
    val lastPrompt: String? = null,
    val lastMessage: String? = null,
    val error: String? = null,
    /** Id запущенных и ещё не остановленных субагентов (`SubagentStart` без `SubagentStop`). */
    val runningAgents: Set<String> = emptySet(),
    /** Основной ход закончился при работающих фоновых агентах; сбрасывается новым ходом (их итоги приходят как промпт). */
    val turnEnded: Boolean = false,
) {
    val activeSubagents: Int get() = runningAgents.size

    /** Статус с учётом «зависших» сессий: процесс мог быть убит без SessionEnd. */
    fun effectiveStatus(now: Long = System.currentTimeMillis(), staleAfter: Duration = Duration.ofMinutes(20)): LiveStatus =
        if (status.active && status != LiveStatus.WAITING_PERMISSION && now - updatedAt > staleAfter.toMillis()) LiveStatus.STALE
        else status

    fun describe(now: Long = System.currentTimeMillis()): String {
        val st = effectiveStatus(now)
        val base = when (st) {
            LiveStatus.TOOL -> listOfNotNull(tool, toolSummary).joinToString(": ").ifEmpty { st.label }
            LiveStatus.WAITING_PERMISSION -> st.label + (tool?.let { ": $it" } ?: "") + (toolSummary?.let { " — $it" } ?: "")
            LiveStatus.ERROR -> st.label + (error?.let { ": $it" } ?: "")
            else -> st.label
        }
        val sub = if (activeSubagents > 0 && st.active) " · " + I18n.msg("live.subagents", activeSubagents) else ""
        return base + sub
    }
}

/** Чистая функция перехода состояния по событию. */
object LiveReducer {
    /** Событие пришло позже более новых: статус уже не его, но запуск/остановку агента учесть надо, иначе id «зависнет». */
    fun applyLate(cur: LiveState, ev: LiveEvent): LiveState {
        val id = ev.agentId ?: return cur
        return when (ev.event) {
            "SubagentStart" -> cur.copy(runningAgents = cur.runningAgents + id)
            "SubagentStop" -> cur.copy(runningAgents = cur.runningAgents - id)
            else -> cur
        }
    }

    fun apply(prev: LiveState?, ev: LiveEvent): LiveState {
        val base = prev ?: LiveState(ev.sessionId, LiveStatus.IDLE, ev.ts, ev.ts)
        fun to(status: LiveStatus, f: (LiveState) -> LiveState = { it }): LiveState {
            val s = base.copy(
                status = status,
                since = if (status != base.status) ev.ts else base.since,
                updatedAt = ev.ts,
                cwd = ev.cwd ?: base.cwd,
                transcriptPath = ev.transcriptPath ?: base.transcriptPath,
                permissionMode = ev.permissionMode ?: base.permissionMode,
            )
            return f(s)
        }
        val fromSubagent = ev.agentId != null
        // Работа фонового агента после конца хода — не новый ход основного: сессия остаётся «ждёт фоновых агентов»
        val inBackground = fromSubagent && base.turnEnded
        return when (ev.event) {
            "SessionStart" -> to(LiveStatus.IDLE) {
                // После сжатия контекста фоновые агенты живы, после запуска/resume/clear — нет
                val agents = if (ev.kind == "compact") it.runningAgents else emptySet()
                it.copy(tool = null, toolSummary = null, error = null, runningAgents = agents, turnEnded = false)
            }
            "UserPromptSubmit" -> to(LiveStatus.WORKING) {
                it.copy(lastPrompt = ev.prompt ?: it.lastPrompt, tool = null, toolSummary = null, error = null, turnEnded = false)
            }
            "PreToolUse" -> if (inBackground) to(LiveStatus.BACKGROUND) { it.copy(tool = null, toolSummary = null) }
            else to(LiveStatus.TOOL) {
                val tool = if (fromSubagent) "${ev.agentType ?: I18n.msg("live.subagent")} › ${ev.toolName}" else ev.toolName
                it.copy(tool = tool, toolSummary = ev.toolSummary)
            }
            "PermissionRequest" -> to(LiveStatus.WAITING_PERMISSION) { it.copy(tool = ev.toolName ?: it.tool, toolSummary = ev.toolSummary ?: it.toolSummary) }
            "PostToolUse", "PostToolUseFailure", "PermissionDenied" ->
                if (base.status == LiveStatus.ENDED || base.status == LiveStatus.IDLE) to(base.status)
                else to(if (inBackground) LiveStatus.BACKGROUND else LiveStatus.WORKING) { it.copy(tool = null, toolSummary = null) }
            "Notification" -> when (ev.kind) {
                "permission_prompt" -> to(LiveStatus.WAITING_PERMISSION) { it.copy(lastMessage = ev.message ?: it.lastMessage) }
                "idle_prompt" -> to(if (base.turnEnded) base.status else LiveStatus.IDLE)
                else -> to(base.status)
            }
            "SubagentStart" -> to(if (base.status.active) base.status else LiveStatus.WORKING) {
                if (ev.agentId != null) it.copy(runningAgents = it.runningAgents + ev.agentId) else it
            }
            // Неизвестные id (служебный агент Claude Code шлёт SubagentStop без SubagentStart) ничего не меняют
            "SubagentStop" -> to(base.status) { if (ev.agentId != null) it.copy(runningAgents = it.runningAgents - ev.agentId) else it }
            "Stop" -> when {
                fromSubagent -> to(base.status)
                // Ход закончился, а фоновые агенты ещё работают — это промежуточный итог, не «Claude закончил»
                base.runningAgents.isNotEmpty() -> to(LiveStatus.BACKGROUND) {
                    it.copy(tool = null, toolSummary = null, lastMessage = ev.lastMessage ?: it.lastMessage, turnEnded = true)
                }
                else -> to(LiveStatus.IDLE) { it.copy(tool = null, toolSummary = null, lastMessage = ev.lastMessage ?: it.lastMessage) }
            }
            "StopFailure" -> to(LiveStatus.ERROR) { it.copy(error = ev.error, tool = null, toolSummary = null) }
            "PreCompact" -> to(LiveStatus.COMPACTING)
            "PostCompact" -> to(if (ev.kind == "manual") LiveStatus.IDLE else LiveStatus.WORKING)
            "SessionEnd" -> to(LiveStatus.ENDED) { it.copy(tool = null, toolSummary = null, runningAgents = emptySet(), turnEnded = false) }
            else -> to(base.status)
        }
    }
}

data class LiveTransition(val before: LiveState?, val after: LiveState)

/**
 * Читает каталог событий и поддерживает состояние всех сессий.
 * Не потокобезопасен — вызывать из одного потока.
 */
class LiveTracker(
    private val eventsDir: Path,
    private val retention: Duration = Duration.ofDays(7),
    private val maxFileBytes: Long = 2L shl 20,
) {
    private val offsets = HashMap<Path, Long>()
    private val states = HashMap<String, LiveState>()

    fun snapshot(): Map<String, LiveState> = HashMap(states)

    /**
     * Дочитывает новые события. Возвращает изменения статусов.
     * При первом вызове (initial=true) изменения считаются, но их удобно игнорировать для уведомлений.
     */
    fun poll(): List<LiveTransition> {
        if (!Files.isDirectory(eventsDir)) return emptyList()
        val files = Files.list(eventsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl") }.toList() }
        val transitions = ArrayList<LiveTransition>()
        for (f in files) {
            val size = try { Files.size(f) } catch (_: Exception) { continue }
            var off = offsets[f] ?: 0L
            if (size < off) off = 0L // файл обрезали
            if (size == off) continue
            val batch = ArrayList<LiveEvent>()
            val newOff = try {
                SessionIndex.readLinesFrom(f, off) { o -> LiveEvent.from(o)?.let(batch::add) }
            } catch (_: Exception) { off }
            offsets[f] = newOff
            if (batch.isEmpty()) continue
            batch.sortBy { it.ts }
            val sid = batch.first().sessionId
            val before = states[sid]
            var cur = before
            for (ev in batch) {
                if (cur != null && ev.ts < cur.updatedAt) { // запоздавшее асинхронное событие
                    cur = LiveReducer.applyLate(cur, ev) // статус не трогаем, но учёт агентов не теряем
                    continue
                }
                cur = LiveReducer.apply(cur, ev)
            }
            if (cur != null && cur != before) {
                states[sid] = cur
                if (before?.status != cur.status || before.tool != cur.tool) transitions += LiveTransition(before, cur)
            }
        }
        return transitions
    }

    /** Удаляет старые файлы событий и обрезает разросшиеся. */
    fun housekeeping(now: Instant = Instant.now()) {
        if (!Files.isDirectory(eventsDir)) return
        val files = Files.list(eventsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl") }.toList() }
        for (f in files) {
            try {
                val mtime = Files.getLastModifiedTime(f).toInstant()
                if (Duration.between(mtime, now) > retention) {
                    Files.deleteIfExists(f)
                    offsets.remove(f)
                    states.remove(f.fileName.toString().removeSuffix(".jsonl"))
                } else if (Files.size(f) > maxFileBytes) {
                    // оставляем только последнюю строку — состояние в памяти уже посчитано
                    var last: String? = null
                    SessionIndex.readRawLinesFrom(f, 0) { last = it }
                    Files.writeString(f, (last ?: "") + "\n")
                    offsets[f] = Files.size(f)
                }
            } catch (_: Exception) {
            }
        }
    }
}
