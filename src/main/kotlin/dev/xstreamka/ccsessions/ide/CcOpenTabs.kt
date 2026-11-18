package dev.xstreamka.ccsessions.ide

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.xstreamka.ccsessions.core.OpenTabsList

/**
 * Что было открыто плагином в проекте: вкладки сессий (claude), вкладки транскриптов и панель CC Sessions.
 * Платформа сама их после перезапуска не возвращает (вкладки — виртуальные файлы в памяти, панель не
 * переоткрывается), поэтому список ведём сами и храним в workspace проекта.
 *
 * Закрытие проекта или выход из IDE список не стирают: в это время вкладки закрывает платформа, а не
 * пользователь, — все удаления после начала закрытия игнорируются (см. [isClosing]).
 */
@Service(Service.Level.PROJECT)
@State(name = "CcSessionsOpenTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CcOpenTabs(private val project: Project) : SimplePersistentStateComponent<CcOpenTabs.S>(S()) {

    class S : BaseState() {
        /** id сессий, открытых плагином во вкладках терминала, в порядке открытия. Форки (id неизвестен) не попадают. */
        var sessions by list<String>()
        /** id сессий, чьи транскрипты открыты во вкладках редактора, в порядке открытия. */
        var transcripts by list<String>()
        /** Панель CC Sessions была видна, когда проект закрывали. */
        var panelVisible by property(false)
    }

    /** Началось закрытие проекта (projectClosingBeforeSave/projectClosing) — с этого момента ничего не удаляем. */
    @Volatile private var closing = false

    /**
     * Видели ли панель открытой в этом запуске. Пока нет — сообщения «панель скрыта» не записываем: при открытии
     * проекта платформа регистрирует окно скрытым и шлёт stateChanged раньше, чем плагин успеет его показать, —
     * иначе сохранённое «панель была открыта» затёрлось бы до восстановления.
     */
    @Volatile private var panelSeenVisible = false

    /** Проект закрывается или IDE выходит: вкладки закрывает платформа, а не пользователь. */
    fun isClosing(): Boolean = closing || appExiting || project.isDisposed

    fun sessionOpened(sessionId: String) {
        synchronized(this) { OpenTabsList.add(state.sessions, sessionId) }
    }

    /** Вкладка сессии завершилась (закрыли её или процесс терминала). Во время закрытия проекта — не трогаем. */
    fun sessionClosed(sessionId: String) {
        if (isClosing()) return
        synchronized(this) { OpenTabsList.remove(state.sessions, sessionId) }
    }

    fun transcriptOpened(sessionId: String) {
        synchronized(this) { OpenTabsList.add(state.transcripts, sessionId) }
    }

    fun transcriptClosed(sessionId: String) {
        if (isClosing()) return
        synchronized(this) { OpenTabsList.remove(state.transcripts, sessionId) }
    }

    /** Видимость панели изменилась (из [ToolWindowManagerListener]). */
    fun panelVisibilityChanged(visible: Boolean) {
        if (isClosing()) return
        if (visible) panelSeenVisible = true
        else if (!panelSeenVisible) return
        setPanelVisible(visible)
    }

    /** Снимки на момент закрытия: только что было на экране — то и вернём при следующем открытии. */
    fun onProjectClosing() {
        if (closing) return
        if (!project.isDisposed) {
            // Окна ещё живы (projectClosingBeforeSave идёт до сохранения и до закрытия окон)
            val tw = ToolWindowManager.getInstance(project).getToolWindow(SessionsToolWindowFactory.ID)
            if (tw != null) setPanelVisible(tw.isVisible)
        }
        closing = true
    }

    /** Копии списков для чтения (восстановление при старте). */
    fun sessions(): List<String> = synchronized(this) { state.sessions.toList() }
    fun transcripts(): List<String> = synchronized(this) { state.transcripts.toList() }
    fun panelVisible(): Boolean = state.panelVisible

    private fun setPanelVisible(visible: Boolean) {
        synchronized(this) { if (state.panelVisible != visible) state.panelVisible = visible }
    }

    companion object {
        /** IDE выходит (после всех подтверждений выхода) — до закрытия проектов. */
        @Volatile internal var appExiting = false

        fun get(project: Project): CcOpenTabs = project.service()

        /** Для обработчиков, которые могут сработать после закрытия проекта: у закрытого проекта сервис не берём. */
        fun ifOpen(project: Project): CcOpenTabs? = if (project.isDisposed) null else get(project)
    }
}

/** Закрытие проекта/выход из IDE: помечаем, что дальнейшие закрытия вкладок — не пользовательские. */
class CcOpenTabsProjectCloseListener : ProjectCloseListener {
    override fun projectClosingBeforeSave(project: Project) {
        CcOpenTabs.ifOpen(project)?.onProjectClosing()
    }

    // На случай закрытия без сохранения: projectClosing тоже идёт раньше, чем платформа закрывает вкладки
    override fun projectClosing(project: Project) {
        CcOpenTabs.ifOpen(project)?.onProjectClosing()
    }
}

/** Выход из IDE: appWillBeClosed идёт после подтверждений выхода (отменить уже нельзя) и до закрытия проектов. */
class CcOpenTabsAppListener : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        CcOpenTabs.appExiting = true
    }
}

/** Открытие/закрытие вкладок транскриптов. */
class CcOpenTabsEditorListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file !is TranscriptVirtualFile) return
        CcOpenTabs.ifOpen(project)?.transcriptOpened(file.sessionId)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file !is TranscriptVirtualFile) return
        // Закрыли «чтобы переоткрыть» (перенос между окнами) или вкладка осталась в другом окне редактора — не удаляем
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return
        if (source.isFileOpen(file)) return
        CcOpenTabs.ifOpen(project)?.transcriptClosed(file.sessionId)
    }
}

/** Видимость панели CC Sessions. */
class CcOpenTabsToolWindowListener(private val project: Project) : ToolWindowManagerListener {
    override fun stateChanged(toolWindowManager: ToolWindowManager) {
        val tw = toolWindowManager.getToolWindow(SessionsToolWindowFactory.ID) ?: return
        CcOpenTabs.ifOpen(project)?.panelVisibilityChanged(tw.isVisible)
    }
}
