package dev.xstreamka.ccsessions.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.xstreamka.ccsessions.core.ClaudePaths
import dev.xstreamka.ccsessions.core.LiveState
import dev.xstreamka.ccsessions.core.LiveTransition
import dev.xstreamka.ccsessions.core.MetaStore
import dev.xstreamka.ccsessions.core.SessionSummary

/** Общие объекты плагина. */
object CcData {
    init {
        CcLanguage.install() // локаль плагина — раньше любого текста
    }

    val paths: ClaudePaths by lazy { ClaudePaths.detect() }
    val meta: MetaStore by lazy { MetaStore(paths.metaFile) }

    const val NOTIFICATION_GROUP = "CC Sessions"

    fun notify(project: Project?, title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}

/** Изменения live-статуса (уровень приложения). */
fun interface LiveStatusListener {
    fun liveChanged(states: Map<String, LiveState>, transitions: List<LiveTransition>)

    companion object {
        @JvmField
        val TOPIC: Topic<LiveStatusListener> = Topic.create("CC Sessions live status", LiveStatusListener::class.java)
    }
}

/** Изменения списка сессий проекта. */
interface SessionsListener {
    fun sessionsChanged(sessions: List<SessionSummary>) {}
    fun sessionUpdated(summary: SessionSummary) {}
    fun metaChanged() {}

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<SessionsListener> = Topic.create("CC Sessions list", SessionsListener::class.java)
    }
}
