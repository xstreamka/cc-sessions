package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SessionsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        CcLanguage.install()
        addPanel(project, toolWindow)
        // Смена языка в настройках: панель создаётся заново — все её тексты (и имя вкладки) в новом языке
        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
            .subscribe(CcLanguageListener.TOPIC, CcLanguageListener {
                if (project.isDisposed) return@CcLanguageListener
                toolWindow.contentManager.removeAllContents(true)
                addPanel(project, toolWindow)
            })
        LiveStatusService.get().ensureStarted()
        LiveNotifier.ensureSubscribed()
    }

    private fun addPanel(project: Project, toolWindow: ToolWindow) {
        val panel = SessionsPanel(project, toolWindow)
        // Имя вкладки выводится в заголовке рядом с названием окна: «CC Sessions · сессии Claude Code»
        val content = ContentFactory.getInstance().createContent(panel, "сессии Claude Code", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Claude Sessions"
    }
}
