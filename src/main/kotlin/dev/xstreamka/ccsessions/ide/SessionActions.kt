package dev.xstreamka.ccsessions.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
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
import dev.xstreamka.ccsessions.core.LiveStatus
import dev.xstreamka.ccsessions.core.MarkdownExport
import dev.xstreamka.ccsessions.core.SessionFiles
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.Shell
import dev.xstreamka.ccsessions.core.TranscriptParser
import java.awt.datatransfer.StringSelection
import java.nio.file.Files

object SessionActions {

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
                project, "Сессия сейчас работает. Завершить её и закрыть вкладку терминала?", "Завершить сессию", null,
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
    abstract class RowAction(text: String, description: String? = null, icon: javax.swing.Icon? = null) :
        DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
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
                "Похоже, эта сессия сейчас работает в другом терминале (${row.live?.describe()}).\n" +
                    "Если продолжить её второй раз, сообщения из двух процессов перемешаются в одном транскрипте.",
                "Сессия уже активна",
                arrayOf("Ответвить копию", "Всё равно продолжить", "Отмена"), 0, Messages.getWarningIcon(),
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
        RowAction("Продолжить", "claude --resume в терминале IDE", AllIcons.Actions.Execute) {
        override fun perform(project: Project, row: SessionRow) {
            if (resume(project, row) && closeTranscript) TranscriptEditorProvider.close(project, row.id)
        }
    }

    class Fork : RowAction("Ответвить (fork)", "Новая сессия с копией истории; оригинал не меняется", AllIcons.Vcs.Branch) {
        override fun perform(project: Project, row: SessionRow) {
            TerminalLauncher.get(project).fork(row.summary, row.meta.alias)
            SessionsService.get(project).requestRefresh()
        }
    }

    class OpenTranscript : RowAction("Открыть транскрипт", null, AllIcons.Actions.Preview) {
        override fun perform(project: Project, row: SessionRow) = TranscriptEditorProvider.open(project, row.id)
    }

    /**
     * Имя сессии для Claude Code (claude --resume <имя>), Air и списка.
     * Открыта во вкладке плагина — отправляем туда /rename (работающий claude сам переписывает название
     * и затёр бы запись в транскрипте); не запущена — дописываем имя в транскрипт, как /rename и Air.
     */
    class Rename : RowAction("Переименовать…", "Имя сессии в Claude Code (claude --resume <имя>), Air и списке", AllIcons.Actions.Edit) {
        override fun perform(project: Project, row: SessionRow) {
            val launcher = TerminalLauncher.get(project)
            if (row.liveStatus?.active == true && !launcher.hasOpenTab(row.id)) {
                Messages.showInfoMessage(
                    project, "Сессия работает в другом терминале — переименуйте её там командой /rename <имя>.", "Переименовать сессию",
                )
                return
            }
            val input = Messages.showInputDialog(
                project, "Имя сессии — его увидят Claude Code (claude --resume <имя>) и Air:",
                "Переименовать сессию", null, row.displayTitle, null,
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

    class ToggleFavorite : RowAction("В избранное / из избранного", null, AllIcons.Nodes.Favorite) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            val fav = e.getData(SessionRow.KEY)?.meta?.favorite == true
            e.presentation.text = if (fav) "Убрать из избранного" else "В избранное"
        }

        override fun perform(project: Project, row: SessionRow) {
            SessionsService.get(project).updateMeta(row.id) { it.copy(favorite = !it.favorite) }
        }
    }

    class ToggleArchive : RowAction("Завершить (в архив)", null, AllIcons.Actions.Checked) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            val row = e.getData(SessionRow.KEY)
            e.presentation.text = if (row?.archived == true) "Вернуть из архива" else "Завершить (в архив)"
        }

        override fun perform(project: Project, row: SessionRow) {
            if (row.archived) AppExecutorUtil.getAppExecutorService().execute { unarchive(project, row) }
            else archive(project, row)
        }
    }

    /** Удалить сессию навсегда: транскрипт и все её служебные файлы (см. [SessionFiles]). */
    class DeleteSession : RowAction("Удалить сессию…", "Удалить транскрипт и служебные файлы сессии навсегда", AllIcons.General.Delete) {
        override fun perform(project: Project, row: SessionRow) {
            if (row.liveStatus?.active == true || TerminalLauncher.get(project).hasOpenTab(row.id)) {
                Messages.showWarningDialog(
                    project, "Сессия сейчас работает или открыта во вкладке терминала. Сначала завершите её.", "Удалить сессию",
                )
                return
            }
            val paths = CcData.paths
            val files = SessionFiles.of(paths, row.id, row.summary.file)
            if (files.isEmpty()) {
                Messages.showInfoMessage(project, "Файлы этой сессии не найдены.", "Удалить сессию")
                return
            }
            val base = paths.configDir.toString().replaceFirst(System.getProperty("user.home"), "~")
            val list = files.joinToString("\n") { "  $base/" + paths.configDir.relativize(it) + if (Files.isDirectory(it)) "/" else "" }
            val size = StringUtil.formatFileSize(SessionFiles.sizeOf(files))
            val answer = Messages.showOkCancelDialog(
                project,
                "Удалить сессию «${row.displayTitle}» навсегда?\n\nБудут удалены ($size):\n$list\n\nОтменить удаление нельзя.",
                "Удалить сессию", "Удалить", "Отмена", Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return
            TranscriptEditorProvider.close(project, row.id)
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    SessionFiles.delete(paths, files)
                    CcData.meta.update(row.id) { SessionMeta() }
                } catch (e: Exception) {
                    thisLogger().warn("cannot delete session ${row.id}", e)
                    CcData.notify(project, "Не удалось удалить сессию", e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
                }
                SessionsService.get(project).requestRefresh()
            }
        }
    }

    class EditTags : RowAction("Теги…") {
        override fun perform(project: Project, row: SessionRow) {
            val text = Messages.showInputDialog(
                project, "Теги через пробел или запятую (поиск: #тег):", "Теги сессии", null,
                row.meta.tags.joinToString(" "), null,
            ) ?: return
            val tags = text.split(Regex("[\\s,]+")).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
            SessionsService.get(project).updateMeta(row.id) { it.copy(tags = tags) }
        }
    }

    class CopyId : RowAction("Копировать ID", null, AllIcons.Actions.Copy) {
        override fun perform(project: Project, row: SessionRow) =
            CopyPasteManager.getInstance().setContents(StringSelection(row.id))
    }

    class CopyResumeCommand : RowAction("Копировать команду resume") {
        override fun perform(project: Project, row: SessionRow) {
            val st = CcSettings.get()
            val cd = row.summary.cwd?.let { "cd " + Shell.quote(it) + " && " }.orEmpty()
            val cmd = cd + Shell.claude(st.claudeCommand.orEmpty(), st.resumeArgs.orEmpty(), listOf("--resume", row.id))
            CopyPasteManager.getInstance().setContents(StringSelection(cmd))
        }
    }

    class ExportMarkdown : RowAction("Экспорт в Markdown…", null, AllIcons.ToolbarDecorator.Export) {
        override fun perform(project: Project, row: SessionRow) {
            val file = row.summary.file ?: return
            val descriptor = FileSaverDescriptor("Экспорт транскрипта", "Сохранить сессию в Markdown", "md")
            val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            val safe = row.displayTitle.replace(Regex("[^\\p{L}\\p{N}._ -]"), "").take(60).trim().ifEmpty { row.id.take(8) }
            val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(base, "$safe.md") ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val t = Files.newBufferedReader(file).useLines { TranscriptParser.parse(row.id, it) }
                val md = MarkdownExport.export(t, row.meta.alias)
                Files.writeString(target.file.toPath(), md)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.file.toPath())
                ApplicationManager.getApplication().invokeLater {
                    if (vf != null && !project.isDisposed) FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        }
    }

    class OpenRawJsonl : RowAction("Открыть исходный .jsonl") {
        override fun perform(project: Project, row: SessionRow) {
            val f = row.summary.file ?: return
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f) ?: return
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    // ---------------- тулбар ----------------

    class NewSession : DumbAwareAction("Новая сессия", "Запустить claude в терминале IDE", AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            startNew(e.project ?: return)
        }
    }

    class Refresh : DumbAwareAction("Обновить", null, AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            SessionsService.get(e.project ?: return).requestRefresh()
        }
    }

    class ToggleAllProjects : ToggleAction("Сессии всех проектов", null, AllIcons.Nodes.ModuleGroup) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = CcSettings.get().showAllProjects
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CcSettings.get().showAllProjects = state
            e.project?.let { SessionsService.get(it).requestRefresh() }
        }
    }

    class ToggleFavoritesOnly : ToggleAction("Только избранное", null, AllIcons.Nodes.Favorite) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = CcSettings.get().favoritesOnly
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CcSettings.get().favoritesOnly = state
            e.project?.let { SessionsService.get(it).requestRefresh() }
        }
    }

    class ToggleGroupByDay : ToggleAction("Группировать по дням", null, AllIcons.Actions.GroupBy) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = CcSettings.get().groupByDay
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CcSettings.get().groupByDay = state
            e.project?.let { SessionsService.get(it).requestRefresh() }
        }
    }

    class InstallHooks : DumbAwareAction("Live-статус…", "Установить/удалить хук статуса сессий", AllIcons.Debugger.Db_set_breakpoint) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            val installed = HookInstaller.isInstalled()
            e.presentation.text = if (installed) "Live-статус включён (нажмите, чтобы выключить)" else "Включить live-статус…"
            e.presentation.icon = if (installed) AllIcons.Debugger.ThreadRunning else AllIcons.Debugger.ThreadSuspended
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project
            if (HookInstaller.isInstalled()) {
                val ok = Messages.showYesNoDialog(project, "Удалить хуки cc-sessions из settings.json?", "Live-статус", null)
                if (ok == Messages.YES) HookInstaller.uninstall(project)
            } else {
                HookInstaller.install(project)
            }
        }
    }

    class OpenSettings : DumbAwareAction("Настройки", null, AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, CcConfigurable::class.java)
        }
    }
}
