package dev.xstreamka.ccsessions.ide

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.xstreamka.ccsessions.core.OpenTabsList
import java.util.concurrent.CancellationException

/**
 * Что было открыто плагином в проекте: вкладки сессий (claude), вкладки транскриптов и панель CC Sessions.
 * Платформа сама их после перезапуска не возвращает (вкладки — виртуальные файлы в памяти, панель не
 * переоткрывается), поэтому список ведём сами и храним в workspace проекта.
 *
 * Закрытие проекта или выход из IDE список не стирают: в это время вкладки закрывает платформа, а не
 * пользователь, — все удаления после начала закрытия игнорируются (см. [isClosing]).
 *
 * Потоки. Списки — неизменяемые, меняются только на EDT заменой ссылки ([onEdt]); [getState] отдаёт новую копию.
 * Поэтому сериализация состояния платформой (она вызывает getState в фоне) никогда не видит список в момент
 * изменения. Удаления всегда откладываются через invokeLater и перепроверяют условия уже на EDT: закрытие
 * проекта и то, что вкладка за это время не открылась снова.
 */
@Service(Service.Level.PROJECT)
@State(name = "CcSessionsOpenTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CcOpenTabs(private val project: Project) : PersistentStateComponent<CcOpenTabs.S> {

    /** Сериализуемый вид состояния; каждый [getState] — отдельная копия, ни с кем не разделяется. */
    class S {
        /** id сессий, открытых плагином во вкладках терминала, в порядке открытия. Форки (id неизвестен) не попадают. */
        var sessions: MutableList<String> = ArrayList()
        /** id сессий, чьи транскрипты открыты во вкладках редактора, в порядке открытия. */
        var transcripts: MutableList<String> = ArrayList()
        /** Панель CC Sessions была видна, когда проект закрывали. */
        var panelVisible: Boolean = false
    }

    @Volatile private var sessions: List<String> = emptyList()
    @Volatile private var transcripts: List<String> = emptyList()
    @Volatile private var panelVisible = false

    /**
     * Проект закрывается — ставится в `projectClosing`, а не в `projectClosingBeforeSave`: после beforeSave закрытие
     * ещё можно отменить (`ProjectManagerImpl.closeProject` после сохранения проверяет `ensureCouldCloseIfUnableToSave`
     * и может вернуть false), и флаг остался бы навсегда. После `projectClosing` проект закрывается без возврата.
     * Платформа закрывает вкладки в `projectClosing`/dispose; наши удаления отложены на EDT и выполняются уже после
     * всех слушателей `projectClosing`, когда флаг стоит, — даже если слушатель терминала сработал раньше нашего.
     */
    @Volatile private var closing = false

    /**
     * Видели ли панель открытой в этом запуске. Пока нет — сообщения «панель скрыта» не записываем: при открытии
     * проекта платформа регистрирует окно скрытым и шлёт stateChanged раньше, чем плагин успеет его показать, —
     * иначе сохранённое «панель была открыта» затёрлось бы до восстановления.
     */
    @Volatile private var panelSeenVisible = false

    override fun getState(): S = S().also {
        it.sessions = ArrayList(sessions)
        it.transcripts = ArrayList(transcripts)
        it.panelVisible = panelVisible
    }

    override fun loadState(state: S) {
        sessions = OpenTabsList.normalized(state.sessions)
        transcripts = OpenTabsList.normalized(state.transcripts)
        panelVisible = state.panelVisible
    }

    /** Проект закрывается или IDE выходит: вкладки закрывает платформа, а не пользователь. */
    fun isClosing(): Boolean = closing || appExiting || project.isDisposed

    fun sessionOpened(sessionId: String) = onEdt { sessions = OpenTabsList.added(sessions, sessionId) }

    /**
     * Вкладка сессии завершилась (закрыли её или процесс терминала). Во время закрытия проекта — не трогаем.
     * [stillOpen] перепроверяется на EDT: если за это время сессию снова открыли, запись остаётся.
     */
    fun sessionClosed(sessionId: String, stillOpen: () -> Boolean) = removeLater {
        if (!stillOpen()) sessions = OpenTabsList.removed(sessions, sessionId)
    }

    fun transcriptOpened(sessionId: String) = onEdt { transcripts = OpenTabsList.added(transcripts, sessionId) }

    fun transcriptClosed(sessionId: String, stillOpen: () -> Boolean) = removeLater {
        if (!stillOpen()) transcripts = OpenTabsList.removed(transcripts, sessionId)
    }

    /**
     * Видимость панели изменилась (из [ToolWindowManagerListener]). [visibleNow] перепроверяется на EDT перед записью
     * «скрыта»: панель могли снова показать, пока отложенная запись ждала очереди.
     */
    fun panelVisibilityChanged(visible: Boolean, visibleNow: () -> Boolean) {
        if (visible) {
            panelSeenVisible = true
            onEdt { if (!isClosing()) panelVisible = true }
        } else if (panelSeenVisible) {
            // «скрыта» — как удаление: во время закрытия проекта окна прячет платформа
            removeLater { if (!visibleNow()) panelVisible = false }
        }
    }

    /** В `projectClosingBeforeSave` (окна ещё живы, сохранение впереди): что сейчас на экране, то и вернём. */
    fun snapshotBeforeSave() {
        if (project.isDisposed) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(SessionsToolWindowFactory.ID) ?: return
        // Пишем сразу (без invokeLater), чтобы значение попало в сохранение, которое идёт следом; запись одного volatile-поля
        panelVisible = tw.isVisible
    }

    /** `projectClosing`: дальше вкладки закрывает только платформа. */
    fun markClosing() {
        closing = true
    }

    /**
     * Убрать из списков то, что при восстановлении пропущено (транскрипта нет, сессия в архиве или уже работает).
     * Как и остальные удаления — отложенно и не во время закрытия проекта; [sessionOpen]/[transcriptOpen]
     * перепроверяются на EDT: если вкладку за это время открыли, запись остаётся.
     */
    fun forget(
        sessionIds: Collection<String>,
        transcriptIds: Collection<String>,
        sessionOpen: (String) -> Boolean,
        transcriptOpen: (String) -> Boolean,
    ) {
        if (sessionIds.isEmpty() && transcriptIds.isEmpty()) return
        removeLater {
            sessions = OpenTabsList.removedAll(sessions, sessionIds.filterNot(sessionOpen))
            transcripts = OpenTabsList.removedAll(transcripts, transcriptIds.filterNot(transcriptOpen))
        }
    }

    /** Списки для чтения (восстановление при старте). */
    fun sessions(): List<String> = sessions
    fun transcripts(): List<String> = transcripts
    fun panelVisible(): Boolean = panelVisible

    /** Добавления — сразу, если уже на EDT (так их увидит ближайшее сохранение), иначе через invokeLater. */
    private fun onEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block, project.disposed)
    }

    /** Удаления — всегда отложенно, с проверкой закрытия проекта уже на EDT (см. [closing]). */
    private fun removeLater(block: () -> Unit) {
        if (isClosing()) return
        ApplicationManager.getApplication().invokeLater({ if (!isClosing()) block() }, project.disposed)
    }

    companion object {
        /** IDE выходит (после всех подтверждений выхода) — до закрытия проектов. */
        @Volatile internal var appExiting = false

        fun get(project: Project): CcOpenTabs = project.service()

        /** Сервис, если проект ещё жив (создаётся при необходимости): для записи об открытии. */
        fun getIfAlive(project: Project): CcOpenTabs? = guard(project) { project.service<CcOpenTabs>() }

        /**
         * Сервис, только если он уже создан и проект жив: для удалений и обработчиков закрытия — проектам, где плагин
         * ничего не открывал, сервис не создаём; поздний вызов во время dispose не бросает исключение.
         */
        fun ifCreated(project: Project): CcOpenTabs? = guard(project) { project.serviceIfCreated<CcOpenTabs>() }

        private inline fun guard(project: Project, get: () -> CcOpenTabs?): CcOpenTabs? {
            if (project.isDisposed) return null
            return try {
                get()
            } catch (e: CancellationException) {
                // AlreadyDisposedException (это PCE, а PCE — CancellationException) или отмена корутины — проект успели
                // закрыть между проверкой и обращением к сервису
                if (project.isDisposed) null else throw e
            }
        }
    }
}

/** Закрытие проекта: снимок панели до сохранения, флаг «закрывается» — когда закрытие уже не отменить. */
class CcOpenTabsProjectCloseListener : ProjectCloseListener {
    override fun projectClosingBeforeSave(project: Project) {
        CcOpenTabs.ifCreated(project)?.snapshotBeforeSave()
    }

    override fun projectClosing(project: Project) {
        CcOpenTabs.ifCreated(project)?.markClosing()
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
        CcOpenTabs.getIfAlive(project)?.transcriptOpened(file.sessionId)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file !is TranscriptVirtualFile) return
        // Закрыли «чтобы переоткрыть» (перенос между окнами) — не удаляем
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return
        val sessionId = file.sessionId
        // Транскрипт остался открытым в другом окне редактора или его успели открыть снова — запись остаётся
        CcOpenTabs.ifCreated(project)?.transcriptClosed(sessionId) {
            source.openFiles.any { it is TranscriptVirtualFile && it.sessionId == sessionId }
        }
    }
}

/** Видимость панели CC Sessions. */
class CcOpenTabsToolWindowListener(private val project: Project) : ToolWindowManagerListener {
    override fun stateChanged(toolWindowManager: ToolWindowManager) {
        val tw = toolWindowManager.getToolWindow(SessionsToolWindowFactory.ID) ?: return
        val visible = tw.isVisible
        // Показали — сервис нужен; скрыли — только если он уже есть (иначе и «видели открытой» не было)
        val tabs = if (visible) CcOpenTabs.getIfAlive(project) else CcOpenTabs.ifCreated(project)
        tabs?.panelVisibilityChanged(visible) { !project.isDisposed && tw.isVisible }
    }
}
