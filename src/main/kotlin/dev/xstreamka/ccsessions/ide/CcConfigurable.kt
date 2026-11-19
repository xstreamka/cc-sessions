package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

class CcConfigurable : BoundConfigurable("CC Sessions") {

    override fun createPanel(): DialogPanel {
        CcLanguage.install()
        val s = CcSettings.get()
        return panel {
            row(CcBundle.message("settings.language.label")) {
                val languages = listOf(CcLanguage.AUTO, CcLanguage.EN, CcLanguage.RU)
                comboBox(languages, textListCellRenderer { lang -> lang?.let { CcBundle.message("settings.language.$it") } })
                    .bindItem({ s.uiLanguage?.takeIf { it in languages } ?: CcLanguage.AUTO }, { s.uiLanguage = it ?: CcLanguage.AUTO })
                    .comment(CcBundle.message("settings.language.comment"))
            }
            group(CcBundle.message("settings.group.launch")) {
                row(CcBundle.message("settings.command")) {
                    textField()
                        .bindText({ s.claudeCommand.orEmpty() }, { s.claudeCommand = it.ifBlank { "claude" } })
                        .columns(COLUMNS_LARGE)
                        .comment(CcBundle.message("settings.command.comment"))
                }
                row(CcBundle.message("settings.newSessionArgs")) {
                    textField().bindText({ s.newSessionArgs.orEmpty() }, { s.newSessionArgs = it }).columns(COLUMNS_LARGE)
                }
                row(CcBundle.message("settings.resumeArgs")) {
                    textField().bindText({ s.resumeArgs.orEmpty() }, { s.resumeArgs = it }).columns(COLUMNS_LARGE)
                        .comment(CcBundle.message("settings.resumeArgs.comment"))
                }
                row {
                    checkBox(CcBundle.message("settings.openInEditor")).bindSelected(s::openInEditor)
                        .comment(CcBundle.message("settings.openInEditor.comment"))
                }
                row {
                    checkBox(CcBundle.message("settings.restoreOnStartup")).bindSelected(s::restoreOnStartup)
                        .comment(CcBundle.message("settings.restoreOnStartup.comment"))
                }
            }
            group(CcBundle.message("settings.group.list")) {
                row { checkBox(CcBundle.message("settings.hideSdk")).bindSelected(s::hideSdkSessions) }
            }
            group(CcBundle.message("settings.group.notifications")) {
                row { checkBox(CcBundle.message("settings.notify.idle")).bindSelected(s::notifyOnIdle) }
                row { checkBox(CcBundle.message("settings.notify.permission")).bindSelected(s::notifyOnPermission) }
                row { checkBox(CcBundle.message("settings.notify.inactive")).bindSelected(s::notifyOnlyWhenInactive) }
                row(CcBundle.message("settings.notify.target")) {
                    comboBox(CcSettings.NOTIFY_TARGETS, textListCellRenderer { t -> t?.let { CcBundle.message("settings.notify.target.$it") } })
                        .bindItem({ CcSettings.notifyTarget() }, { s.notifyTarget = it ?: CcSettings.NOTIFY_IDE })
                        .comment(CcBundle.message("settings.notify.target.comment"))
                }
            }
            group(CcBundle.message("settings.group.live")) {
                row {
                    comment(CcBundle.message("settings.live.comment", CcData.paths.eventsDir.toString()))
                }
                row(CcBundle.message("settings.backupDir")) {
                    textField()
                        .bindText({ s.settingsBackupDir.orEmpty() }, { s.settingsBackupDir = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .comment(CcBundle.message("settings.backupDir.comment", CcData.paths.settingsFile.toString()))
                }
                row {
                    val installed = HookInstaller.isInstalled()
                    label(CcBundle.message(if (installed) "settings.hook.installed" else "settings.hook.notInstalled"))
                    button(CcBundle.message(if (installed) "settings.hook.reinstall" else "button.install")) { HookInstaller.install(currentProject()) }
                    button(CcBundle.message("settings.hook.remove")) { HookInstaller.uninstall(currentProject()) }
                }
            }
        }
    }

    override fun apply() {
        val localeBefore = CcLanguage.locale()
        super.apply()
        if (CcLanguage.locale() != localeBefore) CcLanguage.fireChanged()
        ProjectManager.getInstance().openProjects.forEach { SessionsService.get(it).requestRefresh() }
    }

    private fun currentProject() = ProjectManager.getInstance().openProjects.firstOrNull()
}
