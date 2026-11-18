package dev.xstreamka.ccsessions.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.diagnostic.thisLogger
import dev.xstreamka.ccsessions.core.AirArchive
import dev.xstreamka.ccsessions.core.I18n
import dev.xstreamka.ccsessions.core.LiveStatus
import dev.xstreamka.ccsessions.core.MarkdownExport
import dev.xstreamka.ccsessions.core.SessionFiles
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.Shell
import dev.xstreamka.ccsessions.core.TranscriptParser
import org.jetbrains.annotations.PropertyKey
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.util.function.Supplier
import javax.swing.Icon

object SessionActions {

    /** Текст из бандла, который вычисляется при каждом показе — следует настройке языка плагина без перезапуска. */
    private fun message(@PropertyKey(resourceBundle = I18n.BUNDLE) key: String?): Supplier<String?> =
        Supplier { key?.let { CcBundle.message(it) } }

    /** Текст и описание действия в текущем языке плагина (из update(): шаблон действия сам не перечитывается). */
    private fun Presentation.setTexts(
        @PropertyKey(resourceBundle = I18n.BUNDLE) textKey: String,
        @PropertyKey(resourceBundle = I18n.BUNDLE) descriptionKey: String? = null,
    ) {
        text = CcBundle.message(textKey)
        if (descriptionKey != null) description = CcBundle.message(descriptionKey)
    }

    /** Новая сессия сразу, без вопроса об имени (назвать можно потом через «Переименовать…»). */
    fun startNew(project: Project) {
        TerminalLauncher.get(project).newSession(null)
        SessionsService.get(project).requestRefresh()
    }

    /**
     * Завершить сессию: в архив, закрыть её вкладку терминала, открытую плагином, и вкладку истории (транскрипта).
     * Если сессия сейчас работает — сначала спросить.
     */
    fun archive(project: Project, row: SessionRow) {
        if (row.liveStatus?.active == true) {
            val answer = Messages.showYesNoDialog(
                project, CcBundle.message("dialog.archiveRunning.message"), CcBundle.message("dialog.archiveRunning.title"), null,
            )
            if (answer != Messages.YES) return
        }
        TranscriptEditorProvider.close(project, row.id)
        // Пометку пишем после закрытия вкладки: работающий claude периодически переписывает название и затёр бы её
        TerminalLauncher.get(project).close(row.id) {
            AppExecutorUtil.getAppExecutorService().execute { writeAirArchive(project, row, archived = true) }
        }
    }

    /**
     * Вернуть сессию из архива: снять пометку Air в транскрипте и пометку плагина.
     * Вызывается и перед resume — запись идёт синхронно (одна короткая строка), чтобы claude
     * при старте прочитал уже название без «[archived]».
     */
    fun unarchive(project: Project, row: SessionRow) {
        if (row.summary.archivedInAir) writeAirArchive(project, row, archived = false)
        else if (row.meta.archived) SessionsService.get(project).updateMeta(row.id) { it.copy(archived = false) }
    }

    /**
     * Архив общий с JetBrains Air: дописываем в транскрипт название с префиксом «[archived] » (или без него).
     * Если транскрипт недоступен — запасной вариант, пометка только в meta.json плагина.
     */
    private fun writeAirArchive(project: Project, row: SessionRow, archived: Boolean) {
        val service = SessionsService.get(project)
        try {
            val file = row.summary.file?.takeIf { Files.isRegularFile(it) } ?: error("нет файла транскрипта")
            AirArchive.write(file, row.id, row.summary.baseTitle, archived)
            if (row.meta.archived) service.updateMeta(row.id) { it.copy(archived = false) } else service.requestRefresh()
        } catch (e: Exception) {
            thisLogger().warn("cannot write archive mark to transcript of ${row.id}", e)
            service.updateMeta(row.id) { it.copy(archived = archived) }
        }
    }

    /** Базовый класс для действий над выбранной сессией. */
    abstract class RowAction(
        @PropertyKey(resourceBundle = I18n.BUNDLE) private val textKey: String,
        @PropertyKey(resourceBundle = I18n.BUNDLE) private val descriptionKey: String? = null,
        icon: Icon? = null,
    ) : DumbAwareAction(message(textKey), message(descriptionKey), icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.setTexts(textKey, descriptionKey)
            e.presentation.isEnabled = e.project != null && e.getData(SessionRow.KEY) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val row = e.getData(SessionRow.KEY) ?: return
            perform(project, row)
        }

        abstract fun perform(project: Project, row: SessionRow)
    }

    /**
     * Продолжить сессию (Enter, двойной клик, «Продолжить»). Возвращает true, если сессия открыта
     * (в т.ч. ответвлённая копия из диалога), false — если пользователь отменил.
     */
    fun resume(project: Project, row: SessionRow): Boolean {
        val launcher = TerminalLauncher.get(project)
        if (launcher.focus(row.id)) {
            unarchive(project, row)
            return true
        }
        val st = row.liveStatus
        if (st != null && st.active) {
            val choice = Messages.showDialog(
                project,
                CcBundle.message("dialog.alreadyActive.message", row.live?.describe().toString()),
                CcBundle.message("dialog.alreadyActive.title"),
                arrayOf(
                    CcBundle.message("dialog.alreadyActive.fork"),
                    CcBundle.message("dialog.alreadyActive.resumeAnyway"),
                    CcBundle.message("button.cancel"),
                ),
                0, Messages.getWarningIcon(),
            )
            return when (choice) {
                0 -> {
                    launcher.fork(row.summary, row.meta.alias)
                    true
                }
                1 -> {
                    unarchive(project, row)
                    launcher.resume(row.summary, row.meta.alias)
                    true
                }
                else -> false
            }
        }
        // Из архива возвращаем до запуска: claude при старте берёт название из транскрипта
        unarchive(project, row)
        launcher.resume(row.summary, row.meta.alias)
        return true
    }

    /** [closeTranscript] — действие на тулбаре транскрипта: открыв сессию, закрыть вкладку транскрипта. */
    class Resume(private val closeTranscript: Boolean = false) :
        RowAction("cc.action.resume", "cc.action.resume.description", AllIcons.Actions.Execute) {
        override fun perform(project: Project, row: SessionRow) {
            if (resume(project, row) && closeTranscript) TranscriptEditorProvider.close(project, row.id)
        }
    }

    class Fork : RowAction("cc.action.fork", "cc.action.fork.description", AllIcons.Vcs.Branch) {
        override fun perform(project: Project, row: SessionRow) {
            TerminalLauncher.get(project).fork(row.summary, row.meta.alias)
            SessionsService.get(project).requestRefresh()
        }
    }

    class OpenTranscript : RowAction("cc.action.openTranscript", null, AllIcons.Actions.Preview) {
        override fun perform(project: Project, row: SessionRow) = TranscriptEditorProvider.open(project, row.id)
    }

    /**
     * Имя сессии для Claude Code (claude --resume <имя>), Air и списка.
     * Открыта во вкладке плагина — отправляем туда /rename (работающий claude сам переписывает название
     * и затёр бы запись в транскрипте); не запущена — дописываем имя в транскрипт, как /rename и Air.
     */
    class Rename : RowAction("cc.action.rename", "cc.action.rename.description", AllIcons.Actions.Edit) {
        override fun perform(project: Project, row: SessionRow) {
            val launcher = TerminalLauncher.get(project)
            if (row.liveStatus?.active == true && !launcher.hasOpenTab(row.id)) {
                Messages.showInfoMessage(
                    project, CcBundle.message("dialog.rename.otherTerminal"), CcBundle.message("dialog.rename.title"),
                )
                return
            }
            val input = Messages.showInputDialog(
                project, CcBundle.message("dialog.rename.message"),
                CcBundle.message("dialog.rename.title"), null, row.displayTitle, null,
            ) ?: return
            val name = SessionSummary.oneLine(input, 200)
            val service = SessionsService.get(project)
            val clearAlias = { if (row.meta.alias != null) service.updateMeta(row.id) { it.copy(alias = null) } else service.requestRefresh() }
            if (name.isEmpty()) {
                if (row.meta.alias != null) clearAlias()
                return
            }
            // /rename — только если по live-статусу claude в сессии точно работает: иначе текст ушёл бы в shell вкладки
            val claudeRunning = row.liveStatus.let { it != null && it != LiveStatus.ENDED && it != LiveStatus.STALE }
            val terminalName = Shell.safeTerminalText(name)
            if (claudeRunning && terminalName.isNotEmpty() && launcher.sendToSession(row.id, "/rename $terminalName")) {
                clearAlias()
                return
            }
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    val file = row.summary.file?.takeIf { Files.isRegularFile(it) } ?: error("нет файла транскрипта")
                    // архивная сессия остаётся в архиве
                    AirArchive.write(file, row.id, name, archived = row.summary.archivedInAir)
                    clearAlias()
                } catch (e: Exception) {
                    thisLogger().warn("cannot write session name to transcript of ${row.id}", e)
                    service.updateMeta(row.id) { it.copy(alias = name) }
                }
            }
        }
    }

    class ToggleFavorite : RowAction("cc.action.favorite", null, AllIcons.Nodes.Favorite) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            val fav = e.getData(SessionRow.KEY)?.meta?.favorite == true
            e.presentation.text = CcBundle.message(if (fav) "cc.action.favorite.remove" else "cc.action.favorite.add")
        }

        override fun perform(project: Project, row: SessionRow) {
            SessionsService.get(project).updateMeta(row.id) { it.copy(favorite = !it.favorite) }
        }
    }

    class ToggleArchive : RowAction("cc.action.archive", null, AllIcons.Actions.Checked) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            val row = e.getData(SessionRow.KEY)
            e.presentation.text = CcBundle.message(if (row?.archived == true) "cc.action.unarchive" else "cc.action.archive")
        }

        override fun perform(project: Project, row: SessionRow) {
            if (row.archived) AppExecutorUtil.getAppExecutorService().execute { unarchive(project, row) }
            else archive(project, row)
        }
    }

    /** Удалить сессию навсегда: транскрипт и все её служебные файлы (см. [SessionFiles]). */
    class DeleteSession : RowAction("cc.action.delete", "cc.action.delete.description", AllIcons.General.Delete) {
        override fun perform(project: Project, row: SessionRow) {
            if (row.liveStatus?.active == true || TerminalLauncher.get(project).hasOpenTab(row.id)) {
                Messages.showWarningDialog(
                    project, CcBundle.message("dialog.delete.busy"), CcBundle.message("dialog.delete.title"),
                )
                return
            }
            val paths = CcData.paths
            val files = SessionFiles.of(paths, row.id, row.summary.file)
            if (files.isEmpty()) {
                Messages.showInfoMessage(project, CcBundle.message("dialog.delete.noFiles"), CcBundle.message("dialog.delete.title"))
                return
            }
            val base = paths.configDir.toString().replaceFirst(System.getProperty("user.home"), "~")
            val list = files.joinToString("\n") { "  $base/" + paths.configDir.relativize(it) + if (Files.isDirectory(it)) "/" else "" }
            val size = StringUtil.formatFileSize(SessionFiles.sizeOf(files))
            val answer = Messages.showOkCancelDialog(
                project,
                CcBundle.message("dialog.delete.message", row.displayTitle, size, list),
                CcBundle.message("dialog.delete.title"), CcBundle.message("button.delete"), CcBundle.message("button.cancel"),
                Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return
            TranscriptEditorProvider.close(project, row.id)
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    SessionFiles.delete(paths, files)
                    CcData.meta.update(row.id) { SessionMeta() }
                } catch (e: Exception) {
                    thisLogger().warn("cannot delete session ${row.id}", e)
                    CcData.notify(project, CcBundle.message("notify.deleteFailed"), e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
                }
                SessionsService.get(project).requestRefresh()
            }
        }
    }

    class EditTags : RowAction("cc.action.tags") {
        override fun perform(project: Project, row: SessionRow) {
            val text = Messages.showInputDialog(
                project, CcBundle.message("dialog.tags.message"), CcBundle.message("dialog.tags.title"), null,
                row.meta.tags.joinToString(" "), null,
            ) ?: return
            val tags = text.split(Regex("[\\s,]+")).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
            SessionsService.get(project).updateMeta(row.id) { it.copy(tags = tags) }
        }
    }

    class CopyId : RowAction("cc.action.copyId", null, AllIcons.Actions.Copy) {
        override fun perform(project: Project, row: SessionRow) =
            CopyPasteManager.getInstance().setContents(StringSelection(row.id))
    }

    class CopyResumeCommand : RowAction("cc.action.copyResumeCommand") {
        override fun perform(project: Project, row: SessionRow) {
            val st = CcSettings.get()
            val cd = row.summary.cwd?.let { "cd " + Shell.quote(it) + " && " }.orEmpty()
            val cmd = cd + Shell.claude(st.claudeCommand.orEmpty(), st.resumeArgs.orEmpty(), listOf("--resume", row.id))
            CopyPasteManager.getInstance().setContents(StringSelection(cmd))
        }
    }

    class ExportMarkdown : RowAction("cc.action.exportMarkdown", null, AllIcons.ToolbarDecorator.Export) {
        override fun perform(project: Project, row: SessionRow) {
            val file = row.summary.file ?: return
            val descriptor = FileSaverDescriptor(CcBundle.message("dialog.export.title"), CcBundle.message("dialog.export.description"), "md")
            val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            val safe = row.displayTitle.replace(Regex("[^\\p{L}\\p{N}._ -]"), "").take(60).trim().ifEmpty { row.id.take(8) }
            val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(base, "$safe.md") ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val t = Files.newBufferedReader(file).useLines { TranscriptParser.parse(row.id, it) }
                val md = MarkdownExport.export(t, row.meta.alias)
                Files.writeString(target.file.toPath(), md)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.file.toPath())
                ApplicationManager.getApplication().invokeLater {
                    if (vf != null && !project.isDisposed) OpenFileDescriptor(project, vf).navigate(true)
                }
            }
        }
    }

    class OpenRawJsonl : RowAction("cc.action.openRawJsonl") {
        override fun perform(project: Project, row: SessionRow) {
            val f = row.summary.file ?: return
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f) ?: return
            OpenFileDescriptor(project, vf).navigate(true)
        }
    }

    // ---------------- тулбар ----------------

    /**
     * Новая сессия. Без аргумента — экземпляр из plugin.xml (меню Tools: «Новая сессия Claude Code»),
     * на тулбаре панели — короткий текст [textKey].
     */
    class NewSession(@PropertyKey(resourceBundle = I18n.BUNDLE) private val textKey: String = "action.CcSessions.NewSession.text") :
        DumbAwareAction(message(textKey), message(DESCRIPTION), AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) = e.presentation.setTexts(textKey, DESCRIPTION)

        override fun actionPerformed(e: AnActionEvent) {
            startNew(e.project ?: return)
        }

        private companion object {
            const val DESCRIPTION = "action.CcSessions.NewSession.description"
        }
    }

    class Refresh : DumbAwareAction(message("cc.action.refresh"), AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) = e.presentation.setTexts("cc.action.refresh")

        override fun actionPerformed(e: AnActionEvent) {
            SessionsService.get(e.project ?: return).requestRefresh()
        }
    }

    /** Переключатель на тулбаре панели; текст — из бандла в текущем языке плагина. */
    abstract class KeyedToggle(@PropertyKey(resourceBundle = I18n.BUNDLE) private val textKey: String, icon: Icon) :
        ToggleAction(message(textKey), icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.setTexts(textKey)
        }
    }

    class ToggleAllProjects : KeyedToggle("cc.action.allProjects", AllIcons.Nodes.ModuleGroup) {
        override fun isSelected(e: AnActionEvent) = CcSettings.get().showAllProjects
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CcSettings.get().showAllProjects = state
            e.project?.let { SessionsService.get(it).requestRefresh() }
        }
    }

    class ToggleFavoritesOnly : KeyedToggle("cc.action.favoritesOnly", AllIcons.Nodes.Favorite) {
        override fun isSelected(e: AnActionEvent) = CcSettings.get().favoritesOnly
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CcSettings.get().favoritesOnly = state
            e.project?.let { SessionsService.get(it).requestRefresh() }
        }
    }

    class ToggleGroupByDay : KeyedToggle("cc.action.groupByDay", AllIcons.Actions.GroupBy) {
        override fun isSelected(e: AnActionEvent) = CcSettings.get().groupByDay
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CcSettings.get().groupByDay = state
            e.project?.let { SessionsService.get(it).requestRefresh() }
        }
    }

    class InstallHooks : DumbAwareAction(
        message("action.CcSessions.InstallHooks.text"), message("action.CcSessions.InstallHooks.description"),
        AllIcons.Debugger.Db_set_breakpoint,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            val installed = HookInstaller.isInstalled()
            e.presentation.setTexts(
                if (installed) "cc.action.liveStatus.on" else "cc.action.liveStatus.enable",
                "action.CcSessions.InstallHooks.description",
            )
            e.presentation.icon = if (installed) AllIcons.Debugger.ThreadRunning else AllIcons.Debugger.ThreadSuspended
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project
            if (HookInstaller.isInstalled()) {
                val ok = Messages.showYesNoDialog(project, CcBundle.message("dialog.hooks.remove"), CcBundle.message("dialog.hooks.title"), null)
                if (ok == Messages.YES) HookInstaller.uninstall(project)
            } else {
                HookInstaller.install(project)
            }
        }
    }

    class OpenSettings : DumbAwareAction(message("cc.action.settings"), AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) = e.presentation.setTexts("cc.action.settings")

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, CcConfigurable::class.java)
        }
    }
}
