package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.xstreamka.ccsessions.core.LiveState
import dev.xstreamka.ccsessions.core.LiveTracker
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Следит за ~/.claude/cc-sessions/events (их пишет cc-sessions-hook) и рассылает live-статусы.
 * Один на приложение: файлы событий общие для всех проектов и IDE.
 */
@Service(Service.Level.APP)
class LiveStatusService : Disposable {
    private val tracker = LiveTracker(CcData.paths.eventsDir)
    private val lock = Any()
    @Volatile private var states: Map<String, LiveState> = emptyMap()
    private var future: ScheduledFuture<*>? = null
    private var ticks = 0L
    @Volatile private var started = false

    fun ensureStarted() {
        if (started) return
        synchronized(lock) {
            if (started) return
            started = true
            // первый проход — без уведомлений о «переходах» из прошлого
            safePoll(initial = true)
            future = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay({ safePoll(initial = false) }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun state(sessionId: String): LiveState? = states[sessionId]
    fun all(): Map<String, LiveState> = states

    private fun safePoll(initial: Boolean) {
        try {
            val transitions = synchronized(lock) {
                if (ticks++ % 600 == 0L) tracker.housekeeping()
                val t = tracker.poll()
                states = tracker.snapshot()
                t
            }
            if (transitions.isNotEmpty() || initial) {
                val snapshot = states
                val publish = if (initial) emptyList() else transitions
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().messageBus.syncPublisher(LiveStatusListener.TOPIC).liveChanged(snapshot, publish)
                }
            } else if (ticks % 30 == 0L) {
                // раз в 30 с перерисовываем, чтобы «зависшие» сессии стали STALE
                val snapshot = states
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().messageBus.syncPublisher(LiveStatusListener.TOPIC).liveChanged(snapshot, emptyList())
                }
            }
        } catch (e: Exception) {
            thisLogger().debug("live poll failed", e)
        }
    }

    override fun dispose() {
        future?.cancel(false)
    }

    companion object {
        fun get(): LiveStatusService = service()
    }
}
