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
 */
object CcRestore {
    private val LOG = logger<CcRestore>()

    /** Сколько ждать первого скана индекса; не дождались — сводки берём прямо из файлов. */
    private const val FIRST_SCAN_WAIT_MS = 30_000L

    /** Вызывать из [CcStartup] после запуска индекса и live-статусов. */
    fun start(project: Project) {
        if (!CcSettings.get().restoreOnStartup) return
        val tabs = CcOpenTabs.getIfAlive(project) ?: return
        // Снимок до того, как восстановленные вкладки начнут записываться снова
        val sessionIds = tabs.sessions()
        val transcriptIds = tabs.transcripts()
        if (tabs.panelVisible()) {
            ToolWindowManager.getInstance(project).invokeLater { safely("panel") { showPanel(project) } }
        }
        if (sessionIds.isEmpty() && transcriptIds.isEmpty()) return
        AppExecutorUtil.getAppExecutorService().execute {
            safely("tabs") { restoreTabs(project, tabs, sessionIds, transcriptIds) }
        }
    }

    /** Показать панель без кражи фокуса у редактора (show, а не activate). */
    private fun showPanel(project: Project) {
        if (project.isDisposed) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(SessionsToolWindowFactory.ID) ?: return
        if (!tw.isVisible) tw.show()
    }

    /** Фон: сводки, проверки и план; затем открытие вкладок на EDT. */
    private fun restoreTabs(project: Project, tabs: CcOpenTabs, sessionIds: List<String>, transcriptIds: List<String>) {
        if (project.isDisposed) return
        val svc = SessionsService.get(project)
        val scanned = svc.awaitFirstScan(FIRST_SCAN_WAIT_MS)
        if (!scanned) LOG.info("first session scan is not finished, restoring from transcript files")
        if (project.isDisposed) return
        val meta: Map<String, SessionMeta> = if (scanned) svc.meta else try {
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
            active = { id -> live.state(id)?.effectiveStatus()?.active == true },
        )
        val transcriptPlan = TabsRestore.transcripts(transcriptIds, exists = { id -> transcriptFile(svc, id) != null })
        val toResume = sessionPlan.restore.mapNotNull { id -> summary(id) }
        if (sessionPlan.drop.isNotEmpty() || transcriptPlan.drop.isNotEmpty()) {
            LOG.info("not restoring sessions ${sessionPlan.drop}, transcripts ${transcriptPlan.drop}")
        }

        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            val launcher = TerminalLauncher.get(project)
            tabs.forget(
                sessionPlan.drop, transcriptPlan.drop,
                sessionOpen = launcher::hasOpenTab,
                transcriptOpen = { id ->
                    FileEditorManager.getInstance(project).openFiles.any { it is TranscriptVirtualFile && it.sessionId == id }
                },
            )
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
