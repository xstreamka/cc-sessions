package dev.xstreamka.ccsessions.ide

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.ContentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import dev.xstreamka.ccsessions.core.LiveState
import dev.xstreamka.ccsessions.core.SessionHandoff
import dev.xstreamka.ccsessions.core.Shell
import dev.xstreamka.ccsessions.core.SessionSummary
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Запускает claude во вкладках встроенного терминала (Reworked Terminal API, 2025.3+)
 * и помнит, какая вкладка принадлежит какой сессии, чтобы не открыть одну сессию дважды.
 * По настройке вкладка переносится в область редактора — как штатное действие терминала «Move to Editor».
 */
@Service(Service.Level.PROJECT)
class TerminalLauncher(private val project: Project) : Disposable {
    private val tabs = ConcurrentHashMap<String, TerminalToolWindowTab>()
    /** Вкладки, перенесённые в редактор: сессия → её файл в редакторе. */
    private val editorFiles = ConcurrentHashMap<String, VirtualFile>()
    /** Какой сессии вкладка принадлежит сейчас: после `/clear` или `/resume` claude в той же вкладке ведёт уже другую сессию. */
    private val tabSessions = ConcurrentHashMap<TerminalToolWindowTab, AtomicReference<String>>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LiveStatusListener.TOPIC, LiveStatusListener { states, _ -> followClear(states) })
    }

    override fun dispose() {}

    /**
     * `/clear` или `/resume` во вкладке: claude тот же, сессия другая — вкладка (выделение в списке, восстановление после
     * перезапуска IDE) переходит к новой сессии. Вызывается на EDT при каждом обновлении live-статусов.
     */
    private fun followClear(states: Map<String, LiveState>) {
        if (tabs.isEmpty() || project.isDisposed) return
        var changed = false
        for ((oldId, tab) in tabs.entries.toList()) {
            if (!isAlive(tab)) continue
            val newId = SessionHandoff.successor(oldId, states, tabs.keys) ?: continue
            if (!tabs.remove(oldId, tab)) continue
            tabs[newId] = tab
            editorFiles.remove(oldId)?.let { editorFiles[newId] = it }
            tabSessions[tab]?.set(newId)
            CcOpenTabs.getIfAlive(project)?.sessionReplaced(oldId, newId)
            thisLogger().info("terminal tab moved to session $newId after /clear or /resume in $oldId")
            changed = true
        }
        if (changed) tabsChanged()
    }

    fun hasOpenTab(sessionId: String): Boolean = tabs[sessionId]?.let(::isAlive) == true

    /**
     * Сессии, чьи вкладки сейчас на виду: выбранные в редакторе (во всех его частях) и выбранные в видимом окне Terminal.
     * Только для вкладок, открытых плагином. Вызывать на EDT.
     */
    fun shownSessionIds(): Set<String> {
        if (tabs.isEmpty()) return emptySet()
        val fem = FileEditorManager.getInstance(project)
        val selectedFiles = fem.selectedFiles.toSet()
        val terminalVisible = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)?.isVisible == true
        val result = HashSet<String>()
        for ((id, tab) in tabs) {
            if (!isAlive(tab)) continue
            val file = editorFiles[id]
            if (file != null && fem.isFileOpen(file)) {
                if (file in selectedFiles) result += id
                continue
            }
            // Вкладка в окне Terminal (в т.ч. если её вернули туда из редактора)
            val shown = try {
                val content = tab.content
                terminalVisible && content.manager?.isSelected(content) == true
            } catch (_: Throwable) {
                false
            }
            if (shown) result += id
        }
        return result
    }

    /** Менеджеры содержимого окна Terminal, в которых лежат вкладки плагина, — чтобы следить за переключением вкладок. */
    fun tabContentManagers(): Set<ContentManager> = tabs.values.mapNotNullTo(HashSet()) {
        try {
            it.content.manager
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Продолжить сессию. Если вкладка уже открыта нами — просто показать её.
     * [skip] (восстановление, см. [CcRestore]) проверяется на EDT непосредственно перед созданием вкладки:
     * true — вкладку не создаём.
     */
    fun resume(s: SessionSummary, alias: String?, skip: (() -> Boolean)? = null) {
        if (focus(s.id)) return
        val st = CcSettings.get()
        val cmd = Shell.claude(st.claudeCommand.orEmpty(), st.resumeArgs.orEmpty(), listOf("--resume", s.id))
        launch(s.id, workDirFor(s), tabTitle(alias ?: s.title), cmd, skip)
    }

    /** Ответвить сессию: новая копия с новым ID, оригинал не меняется. */
    fun fork(s: SessionSummary, alias: String?) {
        val st = CcSettings.get()
        val cmd = Shell.claude(st.claudeCommand.orEmpty(), st.resumeArgs.orEmpty(), listOf("--resume", s.id, "--fork-session"))
        launch(null, workDirFor(s), tabTitle("⑂ " + (alias ?: s.title)), cmd)
    }

    /** Новая сессия с заранее известным ID — так вкладка сразу связана с сессией. */
    fun newSession(name: String?) {
        val st = CcSettings.get()
        val id = UUID.randomUUID().toString()
        val args = mutableListOf("--session-id", id)
        if (!name.isNullOrBlank()) args += listOf("-n", name.trim())
        val cmd = Shell.claude(st.claudeCommand.orEmpty(), st.newSessionArgs.orEmpty(), args)
        launch(id, project.basePath, tabTitle(name?.takeIf { it.isNotBlank() } ?: CcBundle.message("terminal.newSession")), cmd)
    }

    /** Выполнить [text] (например, «/rename имя») во вкладке сессии, открытой плагином. false — такой вкладки нет. */
    fun sendToSession(sessionId: String, text: String): Boolean {
        val tab = tabs[sessionId]?.takeIf(::isAlive) ?: return false
        return try {
            tab.view.createSendTextBuilder().shouldExecute().send(text)
            true
        } catch (e: Throwable) {
            if (e is ControlFlowException || e is ProcessCanceledException) throw e
            thisLogger().warn("cannot send text to terminal tab", e)
            false
        }
    }

    fun focus(sessionId: String): Boolean {
        val tab = tabs[sessionId]?.takeIf(::isAlive) ?: return false
        ApplicationManager.getApplication().invokeLater {
            // Вкладка в редакторе — переключаемся на неё; если её вернули в окно Terminal, идём туда
            val file = editorFiles[sessionId]
            val fem = FileEditorManager.getInstance(project)
            if (file != null && fem.isFileOpen(file)) {
                OpenFileDescriptor(project, file).navigate(true)
                return@invokeLater
            }
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID) ?: return@invokeLater
            tw.activate {
                tab.content.manager?.setSelectedContent(tab.content, true)
            }
        }
        return true
    }

    /**
     * Закрыть вкладку терминала сессии, если её открыл плагин и она ещё жива (в редакторе или в окне Terminal).
     * [afterClosed] вызывается, когда вкладки уже нет: сразу, если закрывать нечего, иначе — после завершения
     * вкладки и паузы [PROCESS_EXIT_GRACE_MS], чтобы claude успел дописать транскрипт и выйти.
     * Вызывается ровно один раз; если вкладка так и не завершилась — через [CLOSE_TIMEOUT_MS].
     */
    fun close(sessionId: String, afterClosed: () -> Unit = {}) {
        val called = AtomicBoolean(false)
        val done = { if (called.compareAndSet(false, true)) afterClosed() }
        ApplicationManager.getApplication().invokeLater({
            try {
                val tab = tabs[sessionId]
                val file = editorFiles[sessionId]
                if (tab == null || !isAlive(tab)) {
                    tab?.let { tabs.remove(sessionId, it) }
                    file?.let { editorFiles.remove(sessionId, it) }
                    done()
                    return@invokeLater
                }
                val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
                tab.view.coroutineScope.coroutineContext.job.invokeOnCompletion {
                    scheduler.schedule(done, PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
                }
                scheduler.schedule(done, CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val fem = FileEditorManager.getInstance(project)
                if (file != null && fem.isFileOpen(file)) fem.closeFile(file)
                else TerminalToolWindowTabsManager.getInstance(project).closeTab(tab)
                // Записи убираем только после успешного закрытия: иначе живая вкладка «потеряется» и Resume запустит второй процесс
                tabs.remove(sessionId, tab)
                if (file != null) editorFiles.remove(sessionId, file)
            } catch (e: Throwable) {
                if (e is ControlFlowException || e is ProcessCanceledException) throw e
                thisLogger().warn("cannot close terminal tab", e)
                done()
            }
        }, project.disposed)
    }

    private fun launch(sessionId: String?, workDir: String?, title: String, command: String, skip: (() -> Boolean)? = null) {
        ApplicationManager.getApplication().invokeLater {
            if (skip?.invoke() == true) return@invokeLater
            var registered = false
            try {
                val dir = workDir?.takeIf { Files.isDirectory(Paths.get(it)) } ?: project.basePath
                val inEditor = CcSettings.get().openInEditor
                val manager = TerminalToolWindowTabsManager.getInstance(project)
                val builder = manager.createTabBuilder()
                    .workingDirectory(dir)
                    .tabName(title)
                    .requestFocus(!inEditor)
                val tab = withoutPlatformRestore(builder).createTab()
                tab.view.createSendTextBuilder().shouldExecute().send(command)
                val file = if (inEditor) moveToEditor(manager, tab) else null
                if (sessionId != null) {
                    tabs[sessionId] = tab
                    if (file != null) editorFiles[sessionId] = file
                    val current = AtomicReference(sessionId)
                    tabSessions[tab] = current
                    tab.view.coroutineScope.coroutineContext.job.invokeOnCompletion {
                        tabSessions.remove(tab, current)
                        // Сессия вкладки на момент закрытия — после /clear или /resume уже не та, с которой её открывали
                        val id = current.get()
                        tabs.remove(id, tab)
                        if (file != null) editorFiles.remove(id, file)
                        tabsChanged()
                        // Вкладку закрыли, пока проект открыт, — больше не восстанавливаем; при закрытии проекта/IDE запись остаётся.
                        // Есть другая живая вкладка той же сессии (или её успели открыть снова) — запись тоже остаётся.
                        if (tabs[id] == null) {
                            CcOpenTabs.ifCreated(project)?.sessionClosed(id) { hasOpenTab(id) }
                        }
                    }
                    registered = true
                    tabsChanged()
                }
                if (file == null) ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)?.activate(null)
            } catch (e: Throwable) {
                // API терминала экспериментальное — если что-то пошло не так, не теряем команду
                thisLogger().warn("cannot open terminal tab", e)
                CopyPasteManager.getInstance().setContents(StringSelection(command))
                CcData.notify(
                    project, CcBundle.message("notify.terminalFailed.title"),
                    CcBundle.message("notify.terminalFailed.content", StringUtil.escapeXmlEntities(command)), NotificationType.WARNING,
                )
            }
            // Запоминаем для восстановления после перезапуска IDE — вне try выше: сбой учёта не означает, что вкладка
            // не открылась, и не должен выдавать ошибку терминала (вкладка открыта, команда отправлена)
            if (registered && sessionId != null) {
                try {
                    CcOpenTabs.getIfAlive(project)?.sessionOpened(sessionId)
                } catch (e: Throwable) {
                    if (e is ControlFlowException || e is ProcessCanceledException) throw e
                    thisLogger().warn("cannot remember open terminal tab", e)
                }
            }
        }
    }

    /**
     * Переносит вкладку из окна Terminal в редактор так же, как штатное «Move to Editor».
     * Если не получилось — возвращаем вкладку в окно Terminal, сессия не теряется.
     */
    private fun moveToEditor(manager: TerminalToolWindowTabsManager, tab: TerminalToolWindowTab): VirtualFile? {
        // TerminalViewVirtualFile в Kotlin объявлен internal (в байткоде public) — создаём через рефлексию.
        // Файл создаём до detachTab: если класса не стало, вкладка просто остаётся в окне Terminal.
        val file = try {
            Class.forName(TERMINAL_VIEW_FILE_CLASS, true, TerminalToolWindowTab::class.java.classLoader)
                .getConstructor(TerminalToolWindowTab::class.java)
                .newInstance(tab) as VirtualFile
        } catch (e: Throwable) {
            thisLogger().warn("terminal-in-editor API is unavailable, keeping the tab in the tool window", e)
            return null
        }
        manager.detachTab(tab)
        return try {
            file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true)
            try {
                OpenFileDescriptor(project, file).navigate(true)
            } finally {
                file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)
            }
            file
        } catch (e: Throwable) {
            thisLogger().warn("cannot move terminal tab to editor, keeping it in the tool window", e)
            manager.attachTab(tab)
            null
        }
    }

    /**
     * Вкладки сессий после перезапуска возвращает сам плагин (claude --resume, см. [CcRestore]), поэтому платформа
     * не должна восстанавливать их ещё раз — пустым shell с тем же именем. `restoreOnProjectReopen` есть с 2026.3 (263);
     * вызываем через рефлексию, на 262 метода нет — вкладка создаётся как раньше.
     */
    private fun withoutPlatformRestore(builder: TerminalToolWindowTabBuilder): TerminalToolWindowTabBuilder = try {
        TerminalToolWindowTabBuilder::class.java
            .getMethod("restoreOnProjectReopen", Boolean::class.javaPrimitiveType)
            .invoke(builder, false) as? TerminalToolWindowTabBuilder ?: builder
    } catch (e: Exception) {
        thisLogger().debug("restoreOnProjectReopen is unavailable", e)
        builder
    }

    private fun tabsChanged() {
        if (!project.isDisposed) project.messageBus.syncPublisher(SessionTabsListener.TOPIC).tabsChanged()
    }

    private fun isAlive(tab: TerminalToolWindowTab): Boolean = try {
        tab.view.coroutineScope.isActive
    } catch (_: Throwable) {
        false
    }

    private fun workDirFor(s: SessionSummary): String? = s.cwd ?: project.basePath

    private fun tabTitle(t: String) = "Claude · " + SessionSummary.oneLine(t, 32)

    companion object {
        const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        private const val PROCESS_EXIT_GRACE_MS = 1500L
        private const val CLOSE_TIMEOUT_MS = 15_000L
        private const val TERMINAL_VIEW_FILE_CLASS = "com.intellij.terminal.frontend.editor.TerminalViewVirtualFile"
        fun get(project: Project): TerminalLauncher = project.service()
    }
}

/** Вкладки сессий, открытые плагином, появились или закрылись. Может прийти не на EDT. */
fun interface SessionTabsListener {
    fun tabsChanged()

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<SessionTabsListener> = Topic.create("CC Sessions terminal tabs", SessionTabsListener::class.java)
    }
}
