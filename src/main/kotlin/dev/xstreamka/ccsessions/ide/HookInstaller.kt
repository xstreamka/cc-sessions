package dev.xstreamka.ccsessions.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import dev.xstreamka.ccsessions.core.HooksConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Ставит cc-sessions-hook в ~/.claude/cc-sessions/bin и прописывает его в ~/.claude/settings.json.
 */
object HookInstaller {

    private val binaryName = if (SystemInfo.isWindows) "cc-sessions-hook.exe" else "cc-sessions-hook"

    val binaryPath: Path get() = CcData.paths.binDir.resolve(binaryName)

    private fun resourcePath(): String? {
        val os = when {
            SystemInfo.isLinux -> "linux"
            SystemInfo.isMac -> "darwin"
            SystemInfo.isWindows -> "windows"
            else -> return null
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return "/hook-bin/$os-$arch/$binaryName"
    }

    @Volatile private var cache: Pair<Long, Boolean>? = null

    /** Установлены ли наши хуки. Результат кешируется по mtime settings.json (дёргается из update() действий). */
    fun isInstalled(): Boolean {
        val file = CcData.paths.settingsFile
        val stamp = try { Files.getLastModifiedTime(file).toMillis() } catch (_: Exception) { -1L }
        cache?.let { (s, v) -> if (s == stamp) return v }
        val v = try {
            HooksConfig.isInstalled(HooksConfig.readSettings(file), HooksConfig.commandFor(binaryPath)) && Files.isExecutable(binaryPath)
        } catch (_: Exception) {
            false
        }
        cache = stamp to v
        return v
    }

    /** Копирует бинарник, если он отличается от встроенного в плагин. */
    fun syncBinary(): Boolean {
        val res = resourcePath() ?: return false
        val bytes = HookInstaller::class.java.getResourceAsStream(res)?.use { it.readAllBytes() } ?: return false
        val target = binaryPath
        if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)) return true
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("$binaryName.tmp")
        Files.write(tmp, bytes)
        if (!SystemInfo.isWindows) {
            Files.setPosixFilePermissions(
                tmp,
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                ),
            )
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true
    }

    fun install(project: Project?) {
        val settingsFile = CcData.paths.settingsFile
        val ok = Messages.showOkCancelDialog(
            project,
            CcBundle.message(
                "hooks.install.message",
                settingsFile.toString(), HooksConfig.EVENTS.joinToString(", "), CcData.paths.eventsDir.toString(),
            ),
            CcBundle.message("hooks.install.title"),
            CcBundle.message("button.install"), CcBundle.message("button.cancel"), Messages.getQuestionIcon(),
        )
        if (ok != Messages.OK) return
        try {
            if (!syncBinary()) {
                Messages.showErrorDialog(project, CcBundle.message("hooks.noBinary"), "CC Sessions")
                return
            }
            val settings = HooksConfig.readSettings(settingsFile)
            val changed = HooksConfig.install(settings, HooksConfig.commandFor(binaryPath))
            val backup = if (changed) HooksConfig.writeSettings(settingsFile, settings, CcSettings.backupDir()) else null
            cache = null
            CcData.notify(
                project, CcBundle.message("hooks.enabled.title"),
                if (backup != null) CcBundle.message("hooks.enabled.contentWithBackup", backup.toString())
                else CcBundle.message("hooks.enabled.content"),
            )
            LiveStatusService.get().ensureStarted()
        } catch (e: Exception) {
            thisLogger().warn(e)
            Messages.showErrorDialog(project, CcBundle.message("hooks.error", e.message.toString()), "CC Sessions")
        }
    }

    fun uninstall(project: Project?) {
        try {
            val settingsFile = CcData.paths.settingsFile
            val settings = HooksConfig.readSettings(settingsFile)
            if (HooksConfig.uninstall(settings)) {
                val backup = HooksConfig.writeSettings(settingsFile, settings, CcSettings.backupDir())
                cache = null
                CcData.notify(project, CcBundle.message("hooks.disabled.title"), CcBundle.message("hooks.disabled.content", backup.toString()))
            } else {
                CcData.notify(project, CcBundle.message("hooks.title"), CcBundle.message("hooks.notInstalled"))
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(project, CcBundle.message("hooks.error", e.message.toString()), "CC Sessions")
        }
    }

    /** Разовое предложение включить хуки. */
    fun suggestIfNeeded(project: Project) {
        val st = CcSettings.get()
        if (st.hooksPromptShown || isInstalled()) return
        st.hooksPromptShown = true
        NotificationGroupManager.getInstance().getNotificationGroup(CcData.NOTIFICATION_GROUP)
            .createNotification(
                CcBundle.message("hooks.suggest.title"),
                CcBundle.message("hooks.suggest.content"),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(CcBundle.message("hooks.suggest.enable")) { install(project) })
            .notify(project)
    }
}
