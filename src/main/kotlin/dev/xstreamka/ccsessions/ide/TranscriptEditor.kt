package dev.xstreamka.ccsessions.ide

import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.text.StringUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import dev.xstreamka.ccsessions.core.LiveStatus
import dev.xstreamka.ccsessions.core.MarkdownExport
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.Transcript
import dev.xstreamka.ccsessions.core.TranscriptHtml
import dev.xstreamka.ccsessions.core.TranscriptParser
import dev.xstreamka.ccsessions.core.jsonString
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel

/** Виртуальный файл-«вкладка» транскрипта; один экземпляр на сессию, чтобы не открывать дубликаты. */
class TranscriptVirtualFile(val sessionId: String, title: String) :
    LightVirtualFile(tabName(title)) {
    init {
        isWritable = false
    }

    companion object {
        fun tabName(title: String) = "Claude: " + SessionSummary.oneLine(title, 40).replace('/', '∕')
    }
}

class TranscriptEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is TranscriptVirtualFile
    override fun acceptRequiresReadAction() = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        CcLanguage.install()
        return TranscriptEditor(project, file as TranscriptVirtualFile)
    }
    override fun getEditorTypeId() = "cc-sessions-transcript"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        private val files = ConcurrentHashMap<String, TranscriptVirtualFile>()

        fun open(project: Project, sessionId: String) {
            val svc = SessionsService.get(project)
            val s = svc.find(sessionId)
            val title = svc.meta[sessionId]?.alias ?: s?.title ?: sessionId.take(8)
            val file = files.compute(sessionId) { _, old -> if (old != null && old.isValid) old else TranscriptVirtualFile(sessionId, title) }!!
            OpenFileDescriptor(project, file).navigate(true)
        }

        /** Закрыть вкладку транскрипта сессии, если она открыта (вызывать на EDT). */
        fun close(project: Project, sessionId: String) {
            val file = files[sessionId] ?: return
            val fem = FileEditorManager.getInstance(project)
            if (fem.isFileOpen(file)) fem.closeFile(file)
        }
    }
}

class TranscriptEditor(
    private val project: Project,
    private val file: TranscriptVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val sessionId = file.sessionId
    private val renderer = TranscriptHtml()
    private val root = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            val svc = SessionsService.get(project)
            val s = lastTranscript?.summary ?: svc.find(sessionId) ?: return
            sink[SessionRow.KEY] = SessionRow(s, svc.meta[sessionId] ?: SessionMeta(), LiveStatusService.get().state(sessionId))
        }
    }
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val fallback = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val loading = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val pendingFull = AtomicBoolean(false)
    @Volatile private var loaded = false
    @Volatile private var lastTranscript: Transcript? = null
    private var lastSize = -1L

    init {
        val group = DefaultActionGroup(
            SessionActions.Resume(closeTranscript = true),
            SessionActions.Fork(),
            Separator.getInstance(),
            SessionActions.Rename(),
            SessionActions.ToggleFavorite(),
            SessionActions.ExportMarkdown(),
            SessionActions.OpenRawJsonl(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("CcTranscriptToolbar", group, true)
        toolbar.targetComponent = root
        root.add(toolbar.component, BorderLayout.NORTH)

        if (browser != null && query != null) {
            Disposer.register(this, browser)
            Disposer.register(this, query)
            query.addHandler { payload: String -> openFileFromJs(payload); null }
            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(b: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain) b.executeJavaScript(bridgeJs(), b.url, 0)
                }
            }, browser.cefBrowser)
            browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(b: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                    val url = request.url ?: return false
                    if (userGesture && (url.startsWith("http://") || url.startsWith("https://"))) {
                        BrowserUtil.browse(url)
                        return true
                    }
                    return false
                }
            }, browser.cefBrowser)
            root.add(browser.component, BorderLayout.CENTER)
        } else {
            root.add(JBScrollPane(fallback), BorderLayout.CENTER)
        }

        val conn = project.messageBus.connect(this)
        conn.subscribe(SessionsListener.TOPIC, object : SessionsListener {
            override fun sessionUpdated(summary: SessionSummary) {
                if (summary.id == sessionId) reload(full = false)
            }

            override fun metaChanged() = updateHeader()
        })
        ApplicationManager.getApplication().messageBus.connect(this).apply {
            subscribe(LiveStatusListener.TOPIC, LiveStatusListener { states, _ -> if (states.containsKey(sessionId)) updateHeader() })
            subscribe(LafManagerListener.TOPIC, LafManagerListener { reload(full = true) })
            subscribe(CcLanguageListener.TOPIC, CcLanguageListener { reload(full = true) })
        }
        reload(full = true)
    }

    private fun transcriptPath(): Path? {
        SessionsService.get(project).find(sessionId)?.file?.let { return it }
        LiveStatusService.get().state(sessionId)?.transcriptPath?.let { p -> Paths.get(p).takeIf { Files.isRegularFile(it) }?.let { return it } }
        // сессия из другого проекта — ищем по всем каталогам
        return CcData.paths.allProjectDirs().asSequence().map { it.resolve("$sessionId.jsonl") }.firstOrNull { Files.isRegularFile(it) }
    }

    private fun reload(full: Boolean) {
        if (!loading.compareAndSet(false, true)) {
            if (full) pendingFull.set(true)
            pending.set(true) // догоним после текущей загрузки
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val path = transcriptPath() ?: return@executeOnPooledThread
                val size = Files.size(path)
                if (!full && size == lastSize) return@executeOnPooledThread
                lastSize = size
                val t = Files.newBufferedReader(path).useLines { TranscriptParser.parse(sessionId, it, size) }
                lastTranscript = t
                val opts = options()
                if (browser == null) {
                    val md = MarkdownExport.export(t, meta().alias)
                    ApplicationManager.getApplication().invokeLater { fallback.text = md }
                    return@executeOnPooledThread
                }
                if (full || !loaded) {
                    val html = renderer.document(t, opts)
                        .replace("</head>", "<script>${bridgeJs()}</script></head>")
                    ApplicationManager.getApplication().invokeLater {
                        if (!browser.isDisposed) browser.loadHTML(html)
                        loaded = true
                    }
                } else {
                    val js = "window.ccUpdate && window.ccUpdate(${jsonString(renderer.header(t.summary, opts))}, ${jsonString(renderer.items(t))});"
                    browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
                }
            } catch (e: Exception) {
                thisLogger().warn("cannot render transcript $sessionId", e)
            } finally {
                loading.set(false)
                if (pending.getAndSet(false) && !project.isDisposed) reload(full = pendingFull.getAndSet(false))
            }
        }
    }

    private fun updateHeader() {
        val t = lastTranscript ?: return
        val b = browser ?: return
        if (!loaded) return
        val js = "window.ccHeader && window.ccHeader(${jsonString(renderer.header(t.summary, options()))});"
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    private fun meta(): SessionMeta = SessionsService.get(project).meta[sessionId] ?: SessionMeta()

    private fun options(): TranscriptHtml.Options {
        val scale = JBUIScale.scale(1f).takeIf { it > 0 } ?: 1f
        val css = javaClass.getResource("/viewer/viewer.css")?.readText().orEmpty()
        val js = javaClass.getResource("/viewer/viewer.js")?.readText().orEmpty()
        val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        val live = LiveStatusService.get().state(sessionId)?.takeIf { it.effectiveStatus() != LiveStatus.ENDED }?.describe()
        return TranscriptHtml.Options(
            dark = !JBColor.isBright(),
            css = css,
            js = js,
            alias = meta().alias,
            live = live,
            fontFamily = "'" + JBFont.label().family + "', system-ui, sans-serif",
            monoFamily = "'$editorFont', 'JetBrains Mono', monospace",
            fontSize = (JBFont.label().size2D / scale).toInt().coerceIn(10, 24),
        )
    }

    private fun bridgeJs(): String {
        val q = query ?: return ""
        return "window.ccBridge = function(p) { ${q.inject("p")} };"
    }

    /** Клик по пути файла в транскрипте → открыть в редакторе IDE. */
    private fun openFileFromJs(payload: String) {
        try {
            val o = JsonParser.parseString(payload).asJsonObject
            val raw = o.get("file")?.asString ?: return
            val line = o.get("line")?.asString?.toIntOrNull()
            val cwd = lastTranscript?.summary?.cwd ?: project.basePath
            val path = when {
                raw.startsWith("~/") -> Paths.get(System.getProperty("user.home"), raw.removePrefix("~/"))
                Paths.get(raw).isAbsolute -> Paths.get(raw)
                cwd != null -> Paths.get(cwd).resolve(raw)
                else -> return
            }.normalize()
            ApplicationManager.getApplication().invokeLater {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                if (vf == null) {
                    CcData.notify(project, CcBundle.message("notify.fileNotFound"), StringUtil.escapeXmlEntities(path.toString()), NotificationType.WARNING)
                    return@invokeLater
                }
                OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
            }
        } catch (e: Exception) {
            thisLogger().debug(e)
        }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component ?: fallback
    override fun getName() = CcBundle.message("transcript.editor.name")
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
