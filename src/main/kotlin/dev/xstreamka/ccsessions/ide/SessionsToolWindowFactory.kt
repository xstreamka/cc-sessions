package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SessionsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionsPanel(project, toolWindow)
        // Имя вкладки выводится в заголовке рядом с названием окна: «CC Sessions · сессии Claude Code»
        val content = ContentFactory.getInstance().createContent(panel, "сессии Claude Code", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
        LiveStatusService.get().ensureStarted()
        LiveNotifier.ensureSubscribed()
    }

    companion object {
        const val ID = "Claude Sessions"
    }
}
