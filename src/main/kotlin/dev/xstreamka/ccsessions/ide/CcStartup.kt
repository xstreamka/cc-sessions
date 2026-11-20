package dev.xstreamka.ccsessions.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import dev.xstreamka.ccsessions.core.LiveStatus
import dev.xstreamka.ccsessions.core.LiveTransition
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.beans.PropertyChangeListener
import java.nio.file.Paths
import java.util.concurrent.CancellationException

class CcStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        CcLanguage.install()
        SessionsService.get(project).ensureStarted()
        LiveStatusService.get().ensureStarted()
        LiveNotifier.ensureSubscribed()
        // Вернуть панель и вкладки прошлого запуска: проверки в фоне, вкладки — на EDT, открытие проекта не ждёт
        try {
            CcRestore.start(project)
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            thisLogger().warn("cannot restore open tabs", e)
        }
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

    /** Показанные, но ещё не закрытые уведомления по проектам — их убираем, когда окно проекта получает фокус. EDT. */
    private val shown = HashMap<Project, MutableList<Shown>>()

    /** Показанное уведомление: [alive] — ещё может висеть, [dismiss] — закрыть. */
    private class Shown(val alive: () -> Boolean, val dismiss: () -> Unit)

    fun ensureSubscribed() {
        if (subscribed) return
        synchronized(this) {
            if (subscribed) return
            subscribed = true
            ApplicationManager.getApplication().messageBus.connect(LiveStatusService.get())
                .subscribe(LiveStatusListener.TOPIC, LiveStatusListener { _, transitions -> transitions.forEach(::handle) })
            val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val listener = PropertyChangeListener { e -> (e.newValue as? Window)?.let(::windowActivated) }
            focus.addPropertyChangeListener("activeWindow", listener)
            Disposer.register(LiveStatusService.get()) {
                focus.removePropertyChangeListener("activeWindow", listener)
                shown.clear()
            }
        }
    }

    /** Запомнить уведомление проекта, если включено закрытие по фокусу. Закрытые и истёкшие записи по пути выбрасываем. */
    private fun track(project: Project, notification: Shown) {
        if (!CcSettings.get().notifyDismissOnFocus) return
        shown.keys.removeIf { it.isDisposed }
        shown.values.forEach { list -> list.removeIf { !it.alive() } }
        shown.values.removeIf { it.isEmpty() }
        shown.getOrPut(project) { ArrayList() }.add(notification)
    }

    /** Окно проекта (или его диалог) стало активным — закрыть его уведомления, если это включено в настройках. */
    private fun windowActivated(window: Window) {
        if (!CcSettings.get().notifyDismissOnFocus || shown.isEmpty()) return
        val project = shown.keys.firstOrNull { p ->
            val frame = WindowManager.getInstance().getFrame(p)
            frame != null && generateSequence(window) { it.owner }.any { it === frame }
        } ?: return
        shown.remove(project)?.forEach { if (it.alive()) it.dismiss() }
    }

    private fun handle(t: LiveTransition) {
        val st = CcSettings.get()
        val before = t.before?.status
        val after = t.after.status
        val kind = when {
            after == LiveStatus.WAITING_PERMISSION && before != LiveStatus.WAITING_PERMISSION && st.notifyOnPermission -> "perm"
            after == LiveStatus.IDLE && before != null && before.active && st.notifyOnIdle -> "idle"
            // только в момент конца хода, а не после разрешения, которое запрашивал фоновый агент
            after == LiveStatus.BACKGROUND && t.before?.turnEnded == false && t.after.turnEnded && st.notifyOnBackground -> "background"
            after == LiveStatus.ERROR && st.notifyOnIdle -> "error"
            else -> return
        }
        val project = projectFor(t.after.cwd) ?: return
        if (st.notifyOnlyWhenInactive && isProjectWindowFocused(project)) return
        val summary = SessionsService.get(project).find(t.after.sessionId)
        val alias = SessionsService.get(project).meta[t.after.sessionId]?.alias
        val name = alias ?: summary?.title ?: t.after.sessionId.take(8)
        val (title, type) = when (kind) {
            "perm" -> CcBundle.message("notify.live.permission") to NotificationType.WARNING
            "error" -> CcBundle.message("notify.live.error") to NotificationType.ERROR
            "background" -> CcBundle.message("notify.live.background") to NotificationType.INFORMATION
            else -> CcBundle.message("notify.live.idle") to NotificationType.INFORMATION
        }
        val details = when (kind) {
            "perm" -> t.after.describe()
            "error" -> t.after.error.orEmpty()
            else -> t.after.lastMessage.orEmpty()
        }
        val sid = t.after.sessionId
        val shortDetails = if (details.isNotBlank()) StringUtil.shortenTextWithEllipsis(details, 200, 0) else ""
        val showSession = {
            if (!TerminalLauncher.get(project).focus(sid)) {
                ToolWindowManager.getInstance(project).getToolWindow(SessionsToolWindowFactory.ID)?.activate(null)
            }
        }
        val inIde = {
            if (!project.isDisposed) {
                val content = "<b>" + StringUtil.escapeXmlEntities(name) + "</b>" +
                    (if (shortDetails.isNotEmpty()) "<br>" + StringUtil.escapeXmlEntities(shortDetails) else "")
                val notification = NotificationGroupManager.getInstance().getNotificationGroup(CcData.NOTIFICATION_GROUP)
                    .createNotification(title, content, type)
                    .addAction(NotificationAction.createSimpleExpiring(CcBundle.message("notify.action.show")) { showSession() })
                    .addAction(NotificationAction.createSimple(CcBundle.message("notify.action.transcript")) { TranscriptEditorProvider.open(project, sid) })
                notification.notify(project)
                track(project, Shown({ !notification.isExpired }, notification::expire))
            }
        }
        val target = CcSettings.notifyTarget()
        if (target != CcSettings.NOTIFY_DESKTOP) inIde()
        if (target != CcSettings.NOTIFY_IDE) {
            val desktop = DesktopNotifier.notify(
                title, name + (if (shortDetails.isNotEmpty()) "\n" + shortDetails else ""),
                onClick = {
                    if (!project.isDisposed) {
                        bringToFront(project)
                        showSession()
                    }
                },
                // Не удалось вывести на рабочий стол, а в IDE не показывали — показываем там, чтобы уведомление не потерялось
                onFailure = { if (target == CcSettings.NOTIFY_DESKTOP) inIde() },
            )
            track(project, Shown({ desktop.alive }, desktop::dismiss))
        }
    }

    /**
     * В фокусе окно именно этого проекта (или его диалог). Фокус другого окна той же IDE не считается:
     * сидя в одном проекте, уведомления о сессиях другого проекта надо видеть.
     */
    private fun isProjectWindowFocused(project: Project): Boolean {
        val frame = WindowManager.getInstance().getFrame(project) ?: return ApplicationManager.getApplication().isActive
        var w: Window? = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        while (w != null) {
            if (w === frame) return true
            w = w.owner
        }
        return false
    }

    /** Окно проекта — поверх остальных (по клику на уведомлении рабочего стола). */
    private fun bringToFront(project: Project) {
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        if (frame.extendedState and Frame.ICONIFIED != 0) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        frame.toFront()
        frame.requestFocus()
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
