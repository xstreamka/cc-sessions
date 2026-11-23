package dev.xstreamka.ccsessions.ide

import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.xstreamka.ccsessions.core.DayGroup
import dev.xstreamka.ccsessions.core.LiveState
import dev.xstreamka.ccsessions.core.LiveTransition
import dev.xstreamka.ccsessions.core.SessionGroups
import dev.xstreamka.ccsessions.core.SessionIndex
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class SessionsPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SessionsService.get(project)
    private val model = CollectionListModel<SessionListItem>()
    private val list = SessionsList()
    private val renderer = SessionListRenderer(
        { CcSettings.get().showAllProjects }, { list.hoverIndex }, { list.hoverOnButton }, { list.hoverRow }, { shownTabs },
    )
    private val search = SearchTextField(false)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile private var contentMatches: Set<String> = emptySet()
    private var contentQuery = ""
    private var live: Map<String, LiveState> = LiveStatusService.get().all()
    /** Сессии, чьи вкладки сейчас на виду (см. [TerminalLauncher.shownSessionIds]): их строки подсвечиваются. */
    private var shownTabs: Set<String> = emptySet()
    private var disposed = false
    private val watchedContentManagers: MutableSet<ContentManager> = Collections.newSetFromMap(WeakHashMap())
    private val tabSelectionListener = object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) = scheduleShownTabsUpdate()
    }

    /** Обёртка, через которую действия получают выбранную сессию. */
    private val body = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            sink[SessionRow.KEY] = (list.selectedValue as? SessionListItem.Session)?.row
        }
    }

    init {
        list.cellRenderer = renderer
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = CcBundle.message("panel.empty")
        list.emptyText.appendSecondaryText(CcBundle.message("panel.empty.newSession"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            SessionActions.startNew(project)
        }

        search.textEditor.emptyText.text = CcBundle.message("panel.search.placeholder")
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ onQueryChanged() }, 200)
            }
        })

        val top = JPanel(BorderLayout())
        top.add(search, BorderLayout.CENTER)
        top.border = JBUI.Borders.empty(4, 6)
        body.add(top, BorderLayout.NORTH)
        val scroll = ScrollPaneFactory.createScrollPane(list, true)
        // Прокрутка колёсиком без движения мыши: под курсором уже другая строка, кнопка «Завершить» — за ней
        scroll.viewport.addChangeListener { list.updateHover(list.mousePosition) }
        body.add(scroll, BorderLayout.CENTER)
        setContent(body)

        val am = ActionManager.getInstance()
        val toolbarGroup = DefaultActionGroup(
            SessionActions.NewSession("cc.action.newSession"),
            SessionActions.Refresh(),
            Separator.getInstance(),
            SessionActions.ToggleAllProjects(),
            SessionActions.ToggleFavoritesOnly(),
            SessionActions.ToggleGroupByDay(),
            Separator.getInstance(),
            SessionActions.InstallHooks(),
            SessionActions.OpenSettings(),
        )
        val toolbar = am.createActionToolbar("CcSessionsToolbar", toolbarGroup, true)
        toolbar.targetComponent = body
        setToolbar(toolbar.component)

        val resume = SessionActions.Resume()
        val open = SessionActions.OpenTranscript()
        val rename = SessionActions.Rename()
        val popup = DefaultActionGroup(
            resume,
            SessionActions.Fork(),
            open,
            Separator.getInstance(),
            rename,
            SessionActions.ToggleFavorite(),
            SessionActions.ToggleArchive(),
            SessionActions.EditTags(),
            SessionActions.MoveToProject(),
            Separator.getInstance(),
            SessionActions.CopyId(),
            SessionActions.CopyResumeCommand(),
            SessionActions.ExportMarkdown(),
            SessionActions.OpenRawJsonl(),
            Separator.getInstance(),
            SessionActions.DeleteSession(),
        )
        PopupHandler.installPopupMenu(list, popup, "CcSessionsPopup")
        resume.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), list)
        open.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)), list)
        rename.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)), list)
        // Enter на заголовке группы. Resume на заголовке выключен (SessionRow.KEY = null), так что из двух действий
        // на Enter всегда включено ровно одно: на сессии — Resume, на заголовке — это.
        ToggleSelectedGroup().registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), list)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = (list.selectedValue as? SessionListItem.Session)?.row ?: return false
                SessionActions.resume(project, row)
                return true
            }
        }.installOn(list)

        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val idx = list.locationToIndex(e.point)
                if (idx < 0 || list.getCellBounds(idx, idx)?.contains(e.point) != true) return
                val header = model.getElementAt(idx) as? SessionListItem.Header ?: return
                toggleGroup(header.group)
            }
        })

        val conn = project.messageBus.connect(this)
        conn.subscribe(SessionsListener.TOPIC, object : SessionsListener {
            override fun sessionsChanged(sessions: List<SessionSummary>) = rebuild()
            override fun metaChanged() = rebuild()
        })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LiveStatusListener.TOPIC, LiveStatusListener { states: Map<String, LiveState>, _: List<LiveTransition> ->
                live = states
                rebuild()
            })
        // Переключение вкладок сессий: в редакторе, в окне Terminal (вкладки внутри него — см. updateShownTabs), его показ/скрытие
        conn.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleShownTabsUpdate()
        })
        conn.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = scheduleShownTabsUpdate()
        })
        // Вкладки появились, закрылись или перешли к другой сессии (/clear) — пометки «открыта во вкладке» тоже меняются
        conn.subscribe(SessionTabsListener.TOPIC, SessionTabsListener {
            ApplicationManager.getApplication().invokeLater({ rebuild(); updateShownTabs() }, { project.isDisposed || disposed })
        })

        service.ensureStarted()
        rebuild()
        updateShownTabs()
    }

    /** После события: состояние вкладок к этому моменту может быть ещё не обновлено (и событие может прийти не с EDT). */
    private fun scheduleShownTabsUpdate() {
        ApplicationManager.getApplication().invokeLater(::updateShownTabs, { project.isDisposed || disposed })
    }

    private fun updateShownTabs() {
        val launcher = TerminalLauncher.get(project)
        // Переключение вкладок внутри окна Terminal — событие его менеджера содержимого; подписываемся на каждый один раз
        for (cm in launcher.tabContentManagers()) {
            if (cm.isDisposed || !watchedContentManagers.add(cm)) continue
            cm.addContentManagerListener(tabSelectionListener)
            Disposer.register(this) { if (!cm.isDisposed) cm.removeContentManagerListener(tabSelectionListener) }
        }
        val now = launcher.shownSessionIds()
        if (now != shownTabs) {
            // Переключились на вкладку сессии — ручное выделение снимаем, иначе в списке выделены сразу две строки
            if ((now - shownTabs).isNotEmpty()) list.clearSelection()
            shownTabs = now
            list.repaint()
        }
    }

    private fun query() = search.text.trim()

    private fun onQueryChanged() {
        val q = query()
        rebuild()
        if (q.length < 3 || q.startsWith("#")) {
            contentQuery = ""
            contentMatches = emptySet()
            return
        }
        if (q == contentQuery) return
        contentQuery = q
        val files = service.sessions.mapNotNull { s -> s.file?.let { s.id to it } }
        AppExecutorUtil.getAppExecutorService().execute {
            val hits = files.filter { (_, f) -> SessionIndex.fileContains(f, q) }.map { it.first }.toSet()
            ApplicationManager.getApplication().invokeLater({
                if (query() == q) {
                    contentMatches = hits
                    rebuild()
                }
            }, { project.isDisposed })
        }
    }

    fun rebuild() {
        val st = CcSettings.get()
        val q = query().lowercase()
        val meta = service.meta
        val launcher = TerminalLauncher.get(project)
        val rows = service.sessions.asSequence()
            .filter { !(st.hideSdkSessions && it.isSdk) }
            .map { s -> SessionRow(s, meta[s.id] ?: SessionMeta(), live[s.id], ownTab = launcher.hasOpenTab(s.id)) }
            .filter { !st.favoritesOnly || it.meta.favorite }
            .mapNotNull { r ->
                when {
                    q.isEmpty() -> r
                    metaMatches(r, q) -> r
                    q.length >= 3 && !q.startsWith("#") && r.id in contentMatches -> r.copy(contentMatch = true)
                    else -> null
                }
            }
            .toList()

        val items: List<SessionListItem> = if (!st.groupByDay) {
            rows.sortedWith(compareByDescending<SessionRow> { it.meta.favorite }.thenByDescending { it.summary.updatedAt })
                .map { SessionListItem.Session(it) }
        } else {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val collapsed = if (q.isEmpty()) st.collapsedGroups else emptySet()
            val sorted = rows.sortedByDescending { it.summary.updatedAt }
            SessionGroups.group(sorted) { SessionGroups.groupOf(it.summary.updatedAt, it.meta.favorite, today, zone) }
                .flatMap { (group, groupRows) ->
                    val isCollapsed = group.name in collapsed
                    val header = SessionListItem.Header(group, groupRows.size, isCollapsed)
                    if (isCollapsed) listOf(header) else listOf(header) + groupRows.map { SessionListItem.Session(it) }
                }
        }

        val selected = list.selectedValue
        if (items != model.items) {
            model.replaceAll(items)
            if (selected != null) {
                val idx = items.indexOfFirst { sameItem(it, selected) }
                if (idx >= 0) list.selectedIndex = idx
            }
        } else {
            list.repaint()
        }
        updateToolWindowIcon(rows)
    }

    /**
     * Список с кнопкой «Завершить» у строки-сессии под мышью.
     * Наведение отслеживается до штатных обработчиков (в т.ч. «раскрытия» длинных строк), а нажатие на кнопку
     * вообще не доходит до них: не меняет выделение, не сворачивает группы и не открывает транскрипт.
     */
    private inner class SessionsList : JBList<SessionListItem>(model) {
        /** Индекс строки-сессии под мышью, -1 — нет (заголовки групп не в счёт). */
        var hoverIndex = -1
            private set
        /** Индекс любой строки под мышью (и заголовка группы), -1 — нет: строка подсвечивается. */
        var hoverRow = -1
            private set
        /** Мышь над самой кнопкой «Завершить» — кнопка подсвечивается ярче. */
        var hoverOnButton = false
            private set
        /** Серия кликов началась на кнопке — глушим её до следующего обычного нажатия (двойной клик тоже). */
        private var swallowClicks = false
        private var pressedFinishId: String? = null

        init {
            model.addListDataListener(object : ListDataListener {
                override fun intervalAdded(e: ListDataEvent) = updateHover(mousePosition)
                override fun intervalRemoved(e: ListDataEvent) = updateHover(mousePosition)
                override fun contentsChanged(e: ListDataEvent) = updateHover(mousePosition)
            })
            // Текст строк переносится по ширине: при смене ширины высоты строк надо пересчитать (JList их кеширует)
            addComponentListener(object : ComponentAdapter() {
                private var lastWidth = -1
                override fun componentResized(e: ComponentEvent) {
                    if (width == lastWidth) return
                    lastWidth = width
                    fixedCellHeight = 1
                    fixedCellHeight = -1
                    revalidate()
                    repaint()
                }
            })
        }

        /** Ширина списка = ширина панели: длинный текст переносится, горизонтальной прокрутки нет. */
        override fun getScrollableTracksViewportWidth() = true

        fun updateHover(p: Point?) {
            val row = p?.let(::rowIndexAt) ?: -1
            if (row != hoverRow) {
                hoverRow = row
                repaint()
            }
            val idx = if (row >= 0 && model.getElementAt(row) is SessionListItem.Session) row else -1
            if (idx != hoverIndex) {
                hoverIndex = idx
                repaint()
            }
            val onButton = p != null && finishButtonAt(p) != null
            if (onButton != hoverOnButton) {
                hoverOnButton = onButton
                if (hoverIndex >= 0) getCellBounds(hoverIndex, hoverIndex)?.let { repaint(it) }
            }
        }

        /** Индекс строки под точкой [p] (только если точка внутри ячейки, а не в пустоте под списком), иначе -1. */
        private fun rowIndexAt(p: Point): Int {
            val idx = locationToIndex(p)
            return if (idx >= 0 && getCellBounds(idx, idx)?.contains(p) == true) idx else -1
        }

        private fun sessionIndexAt(p: Point): Int {
            val idx = rowIndexAt(p)
            return if (idx >= 0 && model.getElementAt(idx) is SessionListItem.Session) idx else -1
        }

        /** Сессия, на чью кнопку «Завершить» указывает [p], или null. Кнопка есть только у строки под мышью и не у архивных. */
        private fun finishButtonAt(p: Point): SessionRow? {
            val idx = sessionIndexAt(p)
            if (idx < 0 || idx != hoverIndex) return null
            val row = (model.getElementAt(idx) as SessionListItem.Session).row
            if (row.archived) return null
            val cell = getCellBounds(idx, idx) ?: return null
            return row.takeIf { renderer.finishButtonArea(this, cell).contains(p) }
        }

        override fun getToolTipText(event: MouseEvent?): String? {
            if (event != null && finishButtonAt(event.point) != null) return CcBundle.message("panel.finish.tooltip")
            return super.getToolTipText(event)
        }

        override fun processMouseMotionEvent(e: MouseEvent) {
            updateHover(e.point)
            if (e.id == MouseEvent.MOUSE_DRAGGED && swallowClicks) {
                e.consume()
                return
            }
            super.processMouseMotionEvent(e)
        }

        override fun processMouseEvent(e: MouseEvent) {
            when (e.id) {
                MouseEvent.MOUSE_ENTERED -> updateHover(e.point)
                MouseEvent.MOUSE_EXITED -> updateHover(null)
                MouseEvent.MOUSE_PRESSED -> {
                    // Клик в пустое место под списком снимает выделение (JList по умолчанию выделил бы ближайшую — последнюю — строку);
                    // остаток серии кликов глушим, чтобы двойной клик не открыл сессию, а правый — пустое меню.
                    // Пустой список не трогаем: там текст-заглушка со ссылкой «Начать новую сессию», клик нужен ей
                    if (model.size > 0 && rowIndexAt(e.point) < 0) {
                        clearSelection()
                        if (!isFocusOwner) requestFocusInWindow()
                        swallowClicks = true
                        pressedFinishId = null
                        e.consume()
                        return
                    }
                    // Правый клик сначала выделяет строку под мышью — контекстное меню открывается для неё
                    if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
                        val idx = rowIndexAt(e.point)
                        if (idx >= 0 && idx != selectedIndex) selectedIndex = idx
                    }
                    if (swallowClicks && e.clickCount > 1 && SwingUtilities.isLeftMouseButton(e)) {
                        e.consume()
                        return
                    }
                    swallowClicks = false
                    pressedFinishId = null
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1 && !e.isPopupTrigger) {
                        val row = finishButtonAt(e.point)
                        if (row != null) {
                            swallowClicks = true
                            pressedFinishId = row.id
                            e.consume()
                            return
                        }
                    }
                }
                MouseEvent.MOUSE_RELEASED -> if (swallowClicks) {
                    val id = pressedFinishId
                    pressedFinishId = null
                    // Как у обычной кнопки: срабатывает, если отпустили над той же кнопкой
                    val row = if (id != null && SwingUtilities.isLeftMouseButton(e)) finishButtonAt(e.point) else null
                    if (row != null && row.id == id) {
                        ApplicationManager.getApplication().invokeLater({ SessionActions.archive(project, row) }, project.disposed)
                    }
                    e.consume()
                    return
                }
                MouseEvent.MOUSE_CLICKED -> if (swallowClicks) {
                    e.consume()
                    return
                }
            }
            super.processMouseEvent(e)
        }
    }

    /** Тот же элемент после пересборки: сессия — по id, заголовок — по группе. */
    private fun sameItem(a: SessionListItem, b: SessionListItem): Boolean = when (a) {
        is SessionListItem.Session -> b is SessionListItem.Session && a.row.id == b.row.id
        is SessionListItem.Header -> b is SessionListItem.Header && a.group == b.group
    }

    /** Свернуть/развернуть группу. Пока идёт поиск, группы всегда раскрыты — клик по заголовку ничего не меняет. */
    private fun toggleGroup(group: DayGroup) {
        if (query().isNotEmpty()) return
        val st = CcSettings.get()
        val next = st.collapsedGroups.toMutableSet()
        if (!next.add(group.name)) next.remove(group.name)
        st.collapsedGroups = next
        rebuild()
    }

    /** Enter на выделенном заголовке группы — свернуть/развернуть. */
    private inner class ToggleSelectedGroup : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = list.selectedValue is SessionListItem.Header
        }

        override fun actionPerformed(e: AnActionEvent) {
            val header = list.selectedValue as? SessionListItem.Header ?: return
            toggleGroup(header.group)
        }
    }

    private fun metaMatches(r: SessionRow, q: String): Boolean {
        if (q.startsWith("#")) {
            val tag = q.removePrefix("#")
            return r.meta.tags.any { it.lowercase().startsWith(tag) }
        }
        val s = r.summary
        return listOfNotNull(r.meta.alias, s.title, s.firstPrompt, s.lastPrompt, s.gitBranch, s.id, r.meta.note, s.cwd)
            .any { it.lowercase().contains(q) } || r.meta.tags.any { it.lowercase().contains(q) }
    }

    private fun updateToolWindowIcon(rows: List<SessionRow>) {
        val anyActive = rows.any { it.liveStatus?.active == true }
        toolWindow.setIcon(if (anyActive) ExecutionUtil.getLiveIndicator(CcIcons.ToolWindow) else CcIcons.ToolWindow)
    }

    override fun dispose() {
        disposed = true
    }
}
