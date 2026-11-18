package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class CcConfigurable : BoundConfigurable("CC Sessions") {

    override fun createPanel(): DialogPanel {
        val s = CcSettings.get()
        return panel {
            group("Запуск Claude Code") {
                row("Команда:") {
                    textField()
                        .bindText({ s.claudeCommand.orEmpty() }, { s.claudeCommand = it.ifBlank { "claude" } })
                        .columns(COLUMNS_LARGE)
                        .comment("Например <code>claude</code>, полный путь (<code>~/.npm-global/bin/claude</code>) или <code>npx @anthropic-ai/claude-code</code>")
                }
                row("Аргументы новой сессии:") {
                    textField().bindText({ s.newSessionArgs.orEmpty() }, { s.newSessionArgs = it }).columns(COLUMNS_LARGE)
                }
                row("Аргументы resume / fork:") {
                    textField().bindText({ s.resumeArgs.orEmpty() }, { s.resumeArgs = it }).columns(COLUMNS_LARGE)
                        .comment("Claude Code не восстанавливает bypassPermissions при resume. Если он нужен — добавьте <code>--permission-mode bypassPermissions</code> или задайте <code>permissions.defaultMode</code> в settings.json")
                }
                row {
                    checkBox("Открывать сессию вкладкой в редакторе (рядом с файлами)").bindSelected(s::openInEditor)
                        .comment("Выключено — во вкладке окна Terminal")
                }
            }
            group("Список сессий") {
                row { checkBox("Скрывать сессии, созданные через SDK / claude -p").bindSelected(s::hideSdkSessions) }
            }
            group("Уведомления (нужен live-статус)") {
                row { checkBox("Claude закончил ход").bindSelected(s::notifyOnIdle) }
                row { checkBox("Claude ждёт разрешения").bindSelected(s::notifyOnPermission) }
                row { checkBox("Только когда окно IDE не в фокусе").bindSelected(s::notifyOnlyWhenInactive) }
            }
            group("Live-статус") {
                row {
                    comment(
                        "Асинхронный хук <code>cc-sessions-hook</code> пишет короткие события сессий в " +
                            "<code>${CcData.paths.eventsDir}</code>. Он не блокирует Claude Code и не влияет на ваши хуки.",
                    )
                }
                row("Каталог бэкапов settings.json:") {
                    textField()
                        .bindText({ s.settingsBackupDir.orEmpty() }, { s.settingsBackupDir = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .comment("Перед изменением <code>${CcData.paths.settingsFile}</code> сюда кладётся его копия. Пусто — рядом с settings.json")
                }
                row {
                    val installed = HookInstaller.isInstalled()
                    label(if (installed) "Статус: установлен ✓" else "Статус: не установлен")
                    button(if (installed) "Переустановить" else "Установить") { HookInstaller.install(currentProject()) }
                    button("Удалить") { HookInstaller.uninstall(currentProject()) }
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { SessionsService.get(it).requestRefresh() }
    }

    private fun currentProject() = ProjectManager.getInstance().openProjects.firstOrNull()
}
