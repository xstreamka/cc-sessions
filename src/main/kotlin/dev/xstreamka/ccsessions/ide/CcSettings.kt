package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "CcSessionsSettings", storages = [Storage("cc-sessions.xml")])
class CcSettings : SimplePersistentStateComponent<CcSettings.S>(S()) {

    class S : BaseState() {
        /** Как запускать Claude Code: `claude`, полный путь или `npx @anthropic-ai/claude-code`. */
        var claudeCommand by string("claude")
        /** Доп. аргументы для новой сессии. */
        var newSessionArgs by string("")
        /** Доп. аргументы для resume/fork (bypassPermissions при resume сам не восстанавливается). */
        var resumeArgs by string("")
        /** Открывать терминал сессии вкладкой в редакторе (рядом с файлами), а не в окне Terminal. */
        var openInEditor by property(true)
        /** При открытии проекта вернуть вкладки сессий (снова `claude --resume`), транскрипты и панель, см. [CcRestore]. */
        var restoreOnStartup by property(true)
        var notifyOnIdle by property(true)
        var notifyOnPermission by property(true)
        var notifyOnlyWhenInactive by property(true)
        /** Где показывать уведомления о live-статусе: [NOTIFY_IDE] | [NOTIFY_DESKTOP] | [NOTIFY_BOTH], см. [DesktopNotifier]. */
        var notifyTarget by string(NOTIFY_IDE)
        /** Уведомления на рабочем столе не скрываются сами, пока их не закроют (Linux: срочные, `notify-send -u critical`). */
        var notifyDesktopSticky by property(false)
        var showAllProjects by property(false)
        var favoritesOnly by property(false)
        var hideSdkSessions by property(false)
        /** Показывать сессии группами по дням (Избранное / Сегодня / Вчера / …). */
        var groupByDay by property(true)
        /** Свёрнутые группы — имена `DayGroup.name`. Менять присваиванием нового множества, иначе не сохранится. */
        var collapsedGroups by stringSet()
        var hooksPromptShown by property(false)
        /** Куда класть бэкапы settings.json при установке/удалении хуков; пусто — рядом с settings.json. */
        var settingsBackupDir by string("")
        /** Язык интерфейса плагина: `auto` (как в IDE) | `en` | `ru`, см. [CcLanguage]. */
        var uiLanguage by string(CcLanguage.AUTO)
    }

    companion object {
        const val NOTIFY_IDE = "ide"
        const val NOTIFY_DESKTOP = "desktop"
        const val NOTIFY_BOTH = "both"
        val NOTIFY_TARGETS = listOf(NOTIFY_IDE, NOTIFY_DESKTOP, NOTIFY_BOTH)

        /** Где показывать уведомления; неизвестное значение — как по умолчанию, в IDE. */
        fun notifyTarget(): String = get().notifyTarget?.takeIf { it in NOTIFY_TARGETS } ?: NOTIFY_IDE

        /** Каталог бэкапов из настроек (с раскрытием ~), null — рядом с settings.json. */
        fun backupDir(): java.nio.file.Path? {
            val raw = get().settingsBackupDir?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val expanded = if (raw == "~" || raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw
            return java.nio.file.Paths.get(expanded)
        }

        fun get(): S = service<CcSettings>().state
    }
}
