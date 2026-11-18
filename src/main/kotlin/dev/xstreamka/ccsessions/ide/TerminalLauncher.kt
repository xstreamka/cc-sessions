package dev.xstreamka.ccsessions.ide

import com.intellij.notification.NotificationType
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
import com.intellij.util.concurrency.AppExecutorUtil
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

/**
 * Запускает claude во вкладках встроенного терминала (Reworked Terminal API, 2025.3+)
 * и помнит, какая вкладка принадлежит какой сессии, чтобы не открыть одну сессию дважды.
 * По настройке вкладка переносится в область редактора — как штатное действие терминала «Move to Editor».
 */
@Service(Service.Level.PROJECT)
class TerminalLauncher(private val project: Project) {
    private val tabs = ConcurrentHashMap<String, TerminalToolWindowTab>()
    /** Вкладки, перенесённые в редактор: сессия → её файл в редакторе. */
    private val editorFiles = ConcurrentHashMap<String, VirtualFile>()

    fun hasOpenTab(sessionId: String): Boolean = tabs[sessionId]?.let(::isAlive) == true

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
                    tab.view.coroutineScope.coroutineContext.job.invokeOnCompletion {
                        tabs.remove(sessionId, tab)
                        if (file != null) editorFiles.remove(sessionId, file)
                        // Вкладку закрыли, пока проект открыт, — больше не восстанавливаем; при закрытии проекта/IDE запись остаётся.
                        // Есть другая живая вкладка той же сессии (или её успели открыть снова) — запись тоже остаётся.
                        if (tabs[sessionId] == null) {
                            CcOpenTabs.ifCreated(project)?.sessionClosed(sessionId) { hasOpenTab(sessionId) }
                        }
                    }
                    registered = true
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
