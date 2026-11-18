package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.xstreamka.ccsessions.core.SessionIndex
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Индекс сессий проекта. Раз в 2 секунды проверяет каталоги транскриптов
 * (дешево: только размер/mtime файлов) и дочитывает изменившиеся.
 */
@Service(Service.Level.PROJECT)
class SessionsService(private val project: Project) : Disposable {
    private val index = SessionIndex()
    private val scanning = AtomicBoolean(false)
    // принудительное обновление, запрошенное во время идущего скана, не теряется
    private val pendingForce = AtomicBoolean(false)
    private var future: ScheduledFuture<*>? = null
    /** Первый скан закончился (успешно или нет) — после него [sessions] и [meta] заполнены. */
    private val firstScan = CountDownLatch(1)
    @Volatile var sessions: List<SessionSummary> = emptyList()
        private set
    @Volatile var meta: Map<String, SessionMeta> = emptyMap()
        private set
    private var lastSizes: Map<String, Long> = emptyMap()
    private var lastMetaStamp = -1L

    init {
        // Смена языка плагина: в кешированных сводках остались тексты старого языка — перечитываем транскрипты
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(CcLanguageListener.TOPIC, CcLanguageListener {
                index.clear()
                requestRefresh()
            })
    }

    fun ensureStarted() {
        if (future != null) return
        synchronized(this) {
            if (future != null) return
            future = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay({ refreshNow(false) }, 0, 2, TimeUnit.SECONDS)
        }
    }

    fun requestRefresh() {
        AppExecutorUtil.getAppExecutorService().execute { refreshNow(true) }
    }

    fun projectDirs(): List<Path> {
        val base = project.basePath ?: return emptyList()
        return if (CcSettings.get().showAllProjects) CcData.paths.allProjectDirs() else CcData.paths.findProjectDirs(base)
    }

    fun find(id: String): SessionSummary? = sessions.firstOrNull { it.id == id }

    /** Ждать окончания первого скана (не на EDT). false — не дождались за [timeoutMs]. */
    fun awaitFirstScan(timeoutMs: Long): Boolean = firstScan.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun updateMeta(id: String, f: (SessionMeta) -> SessionMeta) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                CcData.meta.update(id, f)
            } catch (e: Exception) {
                thisLogger().warn("cannot save meta", e)
            }
            refreshNow(true)
        }
    }

    private fun refreshNow(force: Boolean) {
        if (project.isDisposed) return
        if (!scanning.compareAndSet(false, true)) {
            if (force) {
                pendingForce.set(true)
                // скан мог завершиться между проверкой и установкой флага
                if (!scanning.get()) rerunPendingForce()
            }
            return
        }
        try {
            val list = index.scan(projectDirs())
            val metaFile = CcData.paths.metaFile
            val stamp = try { java.nio.file.Files.getLastModifiedTime(metaFile).toMillis() } catch (_: Exception) { 0L }
            val metaChanged = stamp != lastMetaStamp
            if (metaChanged) {
                meta = CcData.meta.all()
                lastMetaStamp = stamp
            }
            val sizes = list.associate { it.id to it.sizeBytes }
            val changedIds = sizes.filter { (id, size) -> lastSizes[id] != size }.keys
            val listChanged = force || metaChanged || sizes.keys != lastSizes.keys || changedIds.isNotEmpty()
            lastSizes = sizes
            sessions = list
            if (!listChanged) return
            ApplicationManager.getApplication().invokeLater({
                val pub = project.messageBus.syncPublisher(SessionsListener.TOPIC)
                pub.sessionsChanged(list)
                if (metaChanged) pub.metaChanged()
                list.filter { it.id in changedIds }.forEach { pub.sessionUpdated(it) }
            }, { project.isDisposed })
        } catch (e: Exception) {
            thisLogger().debug("scan failed", e)
        } finally {
            scanning.set(false)
            firstScan.countDown()
            rerunPendingForce()
        }
    }

    private fun rerunPendingForce() {
        if (pendingForce.getAndSet(false)) {
            AppExecutorUtil.getAppExecutorService().execute { refreshNow(true) }
        }
    }

    override fun dispose() {
        future?.cancel(false)
    }

    companion object {
        fun get(project: Project): SessionsService = project.service()
    }
}
