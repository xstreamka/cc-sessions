package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Уведомление на рабочем столе ОС — само, без настройки IDE «Display system notifications»
 * (платформенный `SystemNotifications` без неё ничего не показывает, а она выводит наружу все уведомления IDE).
 * Linux — `notify-send` (клик по уведомлению вызывает [onClick]), macOS — `osascript` (без клика).
 * Остальные ОС не поддерживаются: сразу вызывается [onFailure].
 */
object DesktopNotifier {
    /**
     * Показанное уведомление. [dismiss] убирает его с рабочего стола и из списка уведомлений ОС — только на Linux:
     * GNOME закрывает уведомление лишь по запросу того, кто его создал, поэтому просим сам notify-send
     * (по SIGINT он закрывает своё уведомление и выходит). Уведомление `osascript` программно не убрать.
     */
    class Handle internal constructor() {
        @Volatile internal var process: Process? = null
        @Volatile var dismissed = false
            private set

        /** Уведомление ещё может висеть: процесс notify-send жив или ещё не запущен. */
        val alive: Boolean get() = !dismissed && process?.isAlive != false

        fun dismiss() {
            if (dismissed) return
            dismissed = true
            process?.let(::interrupt)
        }

        internal fun started(p: Process) {
            process = p
            if (dismissed) interrupt(p) // закрыли раньше, чем процесс успел запуститься
        }

        private fun interrupt(p: Process) {
            if (!SystemInfo.isLinux || !p.isAlive) return
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    ProcessBuilder("kill", "-INT", p.pid().toString()).redirectErrorStream(true).start().waitFor(FAIL_CHECK_MS, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    thisLogger().warn("cannot dismiss desktop notification", e)
                }
            }
        }
    }

    /** Сколько ждать клика: дольше notify-send не держим, уведомление к этому времени давно в истории. */
    private const val WAIT_MINUTES = 30L
    /** Если процесс завершился с ошибкой за это время — показать не удалось (нет демона уведомлений и т.п.). */
    private const val FAIL_CHECK_MS = 2_000L
    private const val CLICK_ACTION = "default"

    /** [onClick] и [onFailure] вызываются на EDT. */
    fun notify(title: String, text: String, onClick: () -> Unit, onFailure: () -> Unit): Handle {
        val handle = Handle()
        val command = when {
            SystemInfo.isLinux -> findInPath("notify-send")?.let {
                val icon = iconFile()?.let { path -> listOf("-i", path.toString()) }.orEmpty()
                val urgency = if (CcSettings.get().notifyDesktopSticky) listOf("-u", "critical") else emptyList()
                listOf(it, "-a", "CC Sessions") + icon + urgency +
                    listOf("-A", "$CLICK_ACTION=" + CcBundle.message("notify.action.show"), title, markupEscape(text))
            }
            SystemInfo.isMac -> listOf(
                "osascript", "-e", "display notification \"${appleEscape(text)}\" with title \"${appleEscape(title)}\"",
            )
            else -> null
        }
        if (command == null) {
            handle.dismiss()
            ApplicationManager.getApplication().invokeLater(onFailure)
            return handle
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                handle.started(process)
                val quick = process.waitFor(FAIL_CHECK_MS, TimeUnit.MILLISECONDS)
                if (quick && handle.dismissed) return@execute // закрыли сами — это не ошибка показа
                if (quick && process.exitValue() != 0) {
                    thisLogger().warn("desktop notification failed (exit ${process.exitValue()}): " + process.inputStream.bufferedReader().readText().trim())
                    ApplicationManager.getApplication().invokeLater(onFailure)
                    return@execute
                }
                if (!quick && !process.waitFor(WAIT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroy()
                    return@execute
                }
                // notify-send с действием печатает имя выбранного действия; клик по самому уведомлению — default
                if (!handle.dismissed && process.inputStream.bufferedReader().readText().lines().any { it.trim() == CLICK_ACTION }) {
                    ApplicationManager.getApplication().invokeLater(onClick)
                }
            } catch (e: Exception) {
                thisLogger().warn("cannot show desktop notification", e)
                ApplicationManager.getApplication().invokeLater(onFailure)
            }
        }
        return handle
    }

    /**
     * Иконка плагина для уведомления: notify-send берёт картинку только из файла, поэтому копируем её из ресурсов
     * в системный каталог IDE (один раз; если не вышло — уведомление просто без иконки).
     */
    @Volatile private var icon: Path? = null

    private fun iconFile(): Path? {
        icon?.takeIf(Files::isRegularFile)?.let { return it }
        return try {
            val target = PathManager.getSystemDir().resolve("cc-sessions").resolve("notification-icon.svg")
            Files.createDirectories(target.parent)
            DesktopNotifier::class.java.getResourceAsStream("/META-INF/pluginIcon.svg")?.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            } ?: return null
            target.also { icon = it }
        } catch (e: Exception) {
            thisLogger().warn("cannot prepare notification icon", e)
            null
        }
    }

    private fun findInPath(name: String): String? =
        (System.getenv("PATH").orEmpty().split(File.pathSeparatorChar) + listOf("/usr/bin", "/usr/local/bin"))
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path

    /** Тело notify-send понимает простую разметку — экранируем, чтобы текст сессии показывался как есть. */
    private fun markupEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun appleEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
