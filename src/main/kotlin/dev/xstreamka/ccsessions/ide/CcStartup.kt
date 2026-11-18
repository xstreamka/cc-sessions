package dev.xstreamka.ccsessions.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import dev.xstreamka.ccsessions.core.LiveStatus
import dev.xstreamka.ccsessions.core.LiveTransition
import java.nio.file.Paths

class CcStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        CcLanguage.install()
        SessionsService.get(project).ensureStarted()
        LiveStatusService.get().ensureStarted()
        LiveNotifier.ensureSubscribed()
        if (HookInstaller.isInstalled()) {
            runCatching { HookInstaller.syncBinary() } // обновляем бинарник после апдейта плагина
        } else {
            HookInstaller.suggestIfNeeded(project)
        }
    }
}

/** Уведомления «Claude закончил» / «ждёт разрешения» — в проект, к которому относится сессия. */
object LiveNotifier {
    @Volatile private var subscribed = false

    fun ensureSubscribed() {
        if (subscribed) return
        synchronized(this) {
            if (subscribed) return
            subscribed = true
            ApplicationManager.getApplication().messageBus.connect(LiveStatusService.get())
                .subscribe(LiveStatusListener.TOPIC, LiveStatusListener { _, transitions -> transitions.forEach(::handle) })
        }
    }

    private fun handle(t: LiveTransition) {
        val st = CcSettings.get()
        val before = t.before?.status
        val after = t.after.status
        val kind = when {
            after == LiveStatus.WAITING_PERMISSION && before != LiveStatus.WAITING_PERMISSION && st.notifyOnPermission -> "perm"
            after == LiveStatus.IDLE && before != null && before.active && st.notifyOnIdle -> "idle"
            after == LiveStatus.ERROR && st.notifyOnIdle -> "error"
            else -> return
        }
        if (st.notifyOnlyWhenInactive && ApplicationManager.getApplication().isActive) return
        val project = projectFor(t.after.cwd) ?: return
        val summary = SessionsService.get(project).find(t.after.sessionId)
        val alias = SessionsService.get(project).meta[t.after.sessionId]?.alias
        val name = alias ?: summary?.title ?: t.after.sessionId.take(8)
        val (title, type) = when (kind) {
            "perm" -> CcBundle.message("notify.live.permission") to NotificationType.WARNING
            "error" -> CcBundle.message("notify.live.error") to NotificationType.ERROR
            else -> CcBundle.message("notify.live.idle") to NotificationType.INFORMATION
        }
        val details = when (kind) {
            "perm" -> t.after.describe()
            "error" -> t.after.error.orEmpty()
            else -> t.after.lastMessage.orEmpty()
        }
        val content = "<b>" + StringUtil.escapeXmlEntities(name) + "</b>" +
            (if (details.isNotBlank()) "<br>" + StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(details, 200, 0)) else "")
        val sid = t.after.sessionId
        NotificationGroupManager.getInstance().getNotificationGroup(CcData.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .addAction(NotificationAction.createSimpleExpiring(CcBundle.message("notify.action.show")) {
                if (!TerminalLauncher.get(project).focus(sid)) {
                    ToolWindowManager.getInstance(project).getToolWindow(SessionsToolWindowFactory.ID)?.activate(null)
                }
            })
            .addAction(NotificationAction.createSimple(CcBundle.message("notify.action.transcript")) { TranscriptEditorProvider.open(project, sid) })
            .notify(project)
    }

    /** Проект, в каталоге которого (или внутри) работала сессия. */
    private fun projectFor(cwd: String?): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && it.basePath != null }
        if (cwd == null) return open.singleOrNull()
        val c = runCatching { Paths.get(cwd).normalize() }.getOrNull() ?: return null
        return open
            .filter { c.startsWith(Paths.get(it.basePath!!).normalize()) }
            .maxByOrNull { it.basePath!!.length }
    }
}
