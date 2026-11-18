package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import dev.xstreamka.ccsessions.core.SessionIndex
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.TabsRestore
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Восстановление после перезапуска IDE того, что плагин открывал в проекте (см. [CcOpenTabs]): панель
 * CC Sessions, вкладки транскриптов и вкладки сессий (снова `claude --resume <id>`, как «Продолжить», без диалогов).
 *
 * Открытие проекта не блокируется: сводки и проверки — в фоне (ждём первый скан индекса), вкладки открываются на
 * EDT, когда окна инструментов проекта уже зарегистрированы ([ToolWindowManager.invokeLater]). Уведомлений нет;
 * ошибки — только в лог. Открытые заново вкладки записываются в [CcOpenTabs] обычным путём — без дублей, в том же
 * порядке; пропущенные id из сохранённых списков убираются — кроме сессий, которые сейчас ведёт другой claude
 * (в том числе в другом открытом проекте): их не открываем, но оставляем в списке до следующего открытия.
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
        // Проект уже закрыт — отметку не ставим: projectClosing её уже снимал, и иначе она осталась бы навсегда
        if (project.isDisposed) return
        if (!markRestored(project)) return
        val tabs = CcOpenTabs.getIfAlive(project) ?: return clearRestoredMarker(project)
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

    /** Сколько после показа панели ждать, не вытеснит ли её окно Project, которое платформа открывает при старте. */
    private const val PROJECT_VIEW_WATCH_MS = 15_000L

    /**
     * Показать панель без кражи фокуса у редактора (show, а не activate).
     *
     * При открытии проекта платформа сама показывает окно Project (`openProjectViewIfNeeded`, 2026.3) — иногда уже
     * после нас; на той же стороне оно вытесняет панель. Поэтому первые [PROJECT_VIEW_WATCH_MS] следим: если панель
     * скрылась и на её стороне показано окно Project — один раз показываем панель снова.
     */
    private fun showPanel(project: Project) {
        if (project.isDisposed) return
        val manager = ToolWindowManager.getInstance(project)
        val tw = manager.getToolWindow(SessionsToolWindowFactory.ID) ?: return
        if (!tw.isVisible) tw.show()

        val watch = Disposer.newDisposable("cc-sessions panel restore")
        Disposer.register(SessionsService.get(project), watch)
        // Слежение закончено (панель уже вернули один раз или вышло время) — дальше ничего не делаем
        val done = AtomicBoolean(false)
        fun stopWatching() {
            if (done.compareAndSet(false, true)) Disposer.dispose(watch)
        }
        project.messageBus.connect(watch).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                if (done.get()) return
                val pv = toolWindowManager.getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
                if (tw.isVisible || !pv.isVisible || pv.anchor != tw.anchor || pv.isSplitMode != tw.isSplitMode) return
                stopWatching()
                toolWindowManager.invokeLater { safely("panel") { if (!project.isDisposed && !tw.isVisible) tw.show() } }
            }
        })
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { ApplicationManager.getApplication().invokeLater(::stopWatching) },
            PROJECT_VIEW_WATCH_MS, TimeUnit.MILLISECONDS,
        )
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
        val now = System.currentTimeMillis()

        // Сводка сессии: из индекса проекта, иначе (сессия из другого проекта) — прямо из её файла
        val index = SessionIndex()
        val summaries = HashMap<String, SessionSummary?>()
        fun summary(id: String): SessionSummary? = summaries.getOrPut(id) {
            svc.find(id)?.takeIf { s -> s.file?.let(Files::isRegularFile) == true }
                ?: TranscriptEditorProvider.transcriptFile(project, id)?.let(index::summarize)
        }

        val sessionPlan = TabsRestore.sessions(
            sessionIds,
            // Пустую сессию (ни одного сообщения) claude не продолжит — считаем, что её нет
            exists = { id -> summary(id)?.isEmpty == false },
            archived = { id -> meta[id]?.archived == true || summary(id)?.archivedInAir == true },
            // Активна по live-статусу (без «зависших») только с событиями после закрытия проекта — иначе это наш
            // claude, убитый с IDE; работающие не открываем, но оставляем в списке
            active = { id -> live.state(id)?.let { TabsRestore.runningElsewhere(it, closedAt, now) } == true },
        )
        val transcriptPlan = TabsRestore.transcripts(
            transcriptIds, exists = { id -> TranscriptEditorProvider.transcriptFile(project, id) != null },
        )
        val toResume = sessionPlan.restore.mapNotNull { id -> summary(id) }
        if (sessionPlan.drop.isNotEmpty() || transcriptPlan.drop.isNotEmpty()) {
            LOG.info("not restoring sessions ${sessionPlan.drop}, transcripts ${transcriptPlan.drop}")
        }
        if (sessionPlan.keep.isNotEmpty()) LOG.info("sessions are running elsewhere, kept for later: ${sessionPlan.keep}")

        ToolWindowManager.getInstance(project).invokeLater {
            if (stopped(project, tabs)) return@invokeLater
            val launcher = TerminalLauncher.get(project)
            forget(project, tabs, sessionPlan.drop, transcriptPlan.drop)
            for (id in transcriptPlan.restore) {
                if (stopped(project, tabs)) return@invokeLater
                safely("transcript $id") { TranscriptEditorProvider.open(project, id) }
            }
            for (s in toResume) {
                if (stopped(project, tabs)) return@invokeLater
                // Проверка ещё раз — в момент запуска вкладки (на EDT): проект могли начать закрывать, а сессию —
                // открыть в другом проекте, восстановление которого запустило вкладку раньше
                safely("session ${s.id}") { launcher.resume(s, meta[s.id]?.alias, skip = { skipLaunch(project, tabs, s.id) }) }
            }
        }
    }

    /** Проект закрыт или закрывается — восстановление прекращаем. */
    private fun stopped(project: Project, tabs: CcOpenTabs): Boolean = project.isDisposed || tabs.isClosing()

    /**
     * Не запускать `claude --resume` во время восстановления (вызывается на EDT перед созданием вкладки): проект
     * закрывается или вкладка этой сессии уже открыта плагином в другом открытом проекте. id остаётся в списке.
     */
    private fun skipLaunch(project: Project, tabs: CcOpenTabs, sessionId: String): Boolean = try {
        when {
            stopped(project, tabs) -> true
            openInOtherProject(project, sessionId) -> {
                LOG.info("session $sessionId is open in another project, kept for later")
                true
            }
            else -> false
        }
    } catch (e: Throwable) {
        // Проект закрывают прямо сейчас — не запускаем; иная ошибка проверки не мешает восстановлению
        if (e is ControlFlowException) true
        else {
            LOG.warn("cannot check session $sessionId in other projects", e)
            false
        }
    }

    /** Вкладка сессии [sessionId] открыта плагином в другом проекте; лаунчер чужого проекта не создаём. */
    private fun openInOtherProject(project: Project, sessionId: String): Boolean =
        ProjectManager.getInstance().openProjects.any { other ->
            other !== project && !other.isDisposed && other.serviceIfCreated<TerminalLauncher>()?.hasOpenTab(sessionId) == true
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
