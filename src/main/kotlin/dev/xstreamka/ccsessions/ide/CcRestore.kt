package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.xstreamka.ccsessions.core.SessionIndex
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.TabsRestore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Восстановление после перезапуска IDE того, что плагин открывал в проекте (см. [CcOpenTabs]): панель
 * CC Sessions, вкладки транскриптов и вкладки сессий (снова `claude --resume <id>`, как «Продолжить», без диалогов).
 *
 * Открытие проекта не блокируется: сводки и проверки — в фоне (ждём первый скан индекса), вкладки открываются на
 * EDT, когда окна инструментов проекта уже зарегистрированы ([ToolWindowManager.invokeLater]). Уведомлений нет;
 * ошибки — только в лог. Открытые заново вкладки записываются в [CcOpenTabs] обычным путём — без дублей, в том же
 * порядке; пропущенные id из сохранённых списков убираются.
 *
 * Восстановление идёт один раз на открытие проекта: отметка в системных свойствах JVM переживает перезагрузку
 * плагина (динамическое обновление) — иначе во вкладки, пережившие перезагрузку, запустился бы второй
 * `claude --resume`. Отметка снимается при закрытии проекта ([clearRestoredMarker]), чтобы при повторном открытии
 * в той же IDE восстановление сработало снова.
 *
 * Настройка выключена — ничего не открываем, а сохранённые списки сессий и транскриптов очищаем: иначе, включив
 * настройку позже, пользователь получил бы давно закрытые сессии. Видимость панели по-прежнему отслеживается.
 */
object CcRestore {
    private val LOG = logger<CcRestore>()

    /** Сколько ждать первого скана индекса; не дождались — сводки берём прямо из файлов. */
    private const val FIRST_SCAN_WAIT_MS = 30_000L

    /** Вызывать из [CcStartup] после запуска индекса и live-статусов. */
    fun start(project: Project) {
        if (!markRestored(project)) return
        val tabs = CcOpenTabs.getIfAlive(project) ?: return
        // Снимок до того, как восстановленные вкладки начнут записываться снова
        val sessionIds = tabs.sessions()
        val transcriptIds = tabs.transcripts()
        if (!CcSettings.get().restoreOnStartup) {
            forget(project, tabs, sessionIds, transcriptIds)
            return
        }
        val closedAt = tabs.closedAt()
        if (tabs.panelVisible()) {
            ToolWindowManager.getInstance(project).invokeLater { safely("panel") { showPanel(project) } }
        }
        if (sessionIds.isEmpty() && transcriptIds.isEmpty()) return
        AppExecutorUtil.getAppExecutorService().execute {
            safely("tabs") { restoreTabs(project, tabs, sessionIds, transcriptIds, closedAt) }
        }
    }

    /** Снять отметку «уже восстанавливали» (закрытие проекта). */
    fun clearRestoredMarker(project: Project) {
        System.clearProperty(markerKey(project))
    }

    /** true — в этом открытии проекта восстановления ещё не было (и теперь отмечено). */
    private fun markRestored(project: Project): Boolean = synchronized(this) {
        val key = markerKey(project)
        if (System.getProperty(key) != null) return false
        System.setProperty(key, "1")
        true
    }

    private fun markerKey(project: Project) = "cc-sessions.restored." + project.locationHash

    /** Убрать id из сохранённых списков, кроме тех, чья вкладка к этому моменту открыта (проверка — на EDT). */
    private fun forget(project: Project, tabs: CcOpenTabs, sessionIds: Collection<String>, transcriptIds: Collection<String>) {
        val launcher = TerminalLauncher.get(project)
        tabs.forget(
            sessionIds, transcriptIds,
            sessionOpen = launcher::hasOpenTab,
            transcriptOpen = { id ->
                FileEditorManager.getInstance(project).openFiles.any { it is TranscriptVirtualFile && it.sessionId == id }
            },
        )
    }

    /** Показать панель без кражи фокуса у редактора (show, а не activate). */
    private fun showPanel(project: Project) {
        if (project.isDisposed) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(SessionsToolWindowFactory.ID) ?: return
        if (!tw.isVisible) tw.show()
    }

    /** Фон: сводки, проверки и план; затем открытие вкладок на EDT. */
    private fun restoreTabs(
        project: Project, tabs: CcOpenTabs, sessionIds: List<String>, transcriptIds: List<String>, closedAt: Long,
    ) {
        if (project.isDisposed) return
        val svc = SessionsService.get(project)
        val scanned = svc.awaitFirstScan(FIRST_SCAN_WAIT_MS)
        if (!scanned) LOG.info("first session scan is not finished, restoring from transcript files")
        if (project.isDisposed) return
        // Скан не дождались или он упал до чтения меты (мета пуста) — читаем её сами: нужна для проверки архива
        val meta: Map<String, SessionMeta> = svc.meta.takeIf { scanned && it.isNotEmpty() } ?: try {
            CcData.meta.all()
        } catch (e: Exception) {
            LOG.warn("cannot read session meta", e)
            emptyMap()
        }
        val live = LiveStatusService.get()

        // Сводка сессии: из индекса проекта, иначе (сессия из другого проекта) — прямо из её файла
        val index = SessionIndex()
        val summaries = HashMap<String, SessionSummary?>()
        fun summary(id: String): SessionSummary? = summaries.getOrPut(id) {
            svc.find(id)?.takeIf { s -> s.file?.let(Files::isRegularFile) == true }
                ?: transcriptFile(svc, id)?.let(index::summarize)
        }

        val sessionPlan = TabsRestore.sessions(
            sessionIds,
            // Пустую сессию (ни одного сообщения) claude не продолжит — считаем, что её нет
            exists = { id -> summary(id)?.isEmpty == false },
            archived = { id -> meta[id]?.archived == true || summary(id)?.archivedInAir == true },
            // Активна по live-статусу только с событиями после закрытия проекта — иначе это наш claude, убитый с IDE
            active = { id -> live.state(id)?.let { TabsRestore.runningElsewhere(it.status.active, it.updatedAt, closedAt) } == true },
        )
        val transcriptPlan = TabsRestore.transcripts(transcriptIds, exists = { id -> transcriptFile(svc, id) != null })
        val toResume = sessionPlan.restore.mapNotNull { id -> summary(id) }
        if (sessionPlan.drop.isNotEmpty() || transcriptPlan.drop.isNotEmpty()) {
            LOG.info("not restoring sessions ${sessionPlan.drop}, transcripts ${transcriptPlan.drop}")
        }

        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            val launcher = TerminalLauncher.get(project)
            forget(project, tabs, sessionPlan.drop, transcriptPlan.drop)
            for (id in transcriptPlan.restore) {
                safely("transcript $id") { TranscriptEditorProvider.open(project, id) }
            }
            for (s in toResume) {
                safely("session ${s.id}") { launcher.resume(s, meta[s.id]?.alias) }
            }
        }
    }

    /**
     * Файл транскрипта сессии [id]: из индекса, по live-статусу или в каталогах всех проектов (как в транскрипте).
     * id берётся из workspace проекта — разделители путей в нём не допускаем.
     */
    private fun transcriptFile(svc: SessionsService, id: String): Path? {
        if (id.isBlank() || id.any { it == '/' || it == '\\' } || id.startsWith(".")) return null
        svc.find(id)?.file?.takeIf(Files::isRegularFile)?.let { return it }
        LiveStatusService.get().state(id)?.transcriptPath
            ?.let { p -> runCatching { Paths.get(p) }.getOrNull() }
            ?.takeIf { it.fileName?.toString() == "$id.jsonl" && Files.isRegularFile(it) }
            ?.let { return it }
        return CcData.paths.allProjectDirs().asSequence().map { it.resolve("$id.jsonl") }.firstOrNull(Files::isRegularFile)
    }

    /** Ошибка одной вкладки не мешает остальным и не роняет открытие проекта — только в лог. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            LOG.warn("cannot restore $what", e)
        }
    }
}
