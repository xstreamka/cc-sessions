package dev.xstreamka.ccsessions.ide

import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic
import dev.xstreamka.ccsessions.core.I18n
import org.jetbrains.annotations.PropertyKey
import java.util.Locale

/**
 * Язык интерфейса плагина: настройка [CcSettings.S.uiLanguage] (`auto` | `en` | `ru`).
 * При первом обращении к объекту подставляет свою локаль в [I18n.localeProvider] — поэтому [install] вызывается
 * в точках входа плагина (тул-окно, стартовая активность, транскрипт, настройки, [CcData], [CcBundle]) до первого текста.
 */
object CcLanguage {
    const val AUTO = "auto"
    const val EN = "en"
    const val RU = "ru"

    private val RU_LOCALE: Locale = Locale.of(RU)

    init {
        I18n.localeProvider = ::locale
    }

    /** Ничего не делает сам: обращение к объекту выполняет `init`, который ставит [I18n.localeProvider]. */
    fun install() {}

    /** `en` → английский (базовый бандл), `ru` → русский, `auto` → русский, если IDE на русском, иначе английский. */
    fun locale(): Locale = when (CcSettings.get().uiLanguage) {
        EN -> Locale.ROOT
        RU -> RU_LOCALE
        else -> if (DynamicBundle.getLocale().language == RU) RU_LOCALE else Locale.ROOT
    }

    /** Сообщить открытым панелям и транскриптам, что язык сменился (вызывать на EDT). */
    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(CcLanguageListener.TOPIC).languageChanged()
    }
}

/** Тексты IDE-слоя плагина в текущем языке. */
object CcBundle {
    init {
        CcLanguage.install()
    }

    fun message(@PropertyKey(resourceBundle = I18n.BUNDLE) key: String, vararg args: Any?): String = I18n.msg(key, *args)
}

/** Смена языка плагина в настройках (уровень приложения): перестроить то, что уже показано. */
fun interface CcLanguageListener {
    fun languageChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<CcLanguageListener> = Topic.create("CC Sessions language", CcLanguageListener::class.java)
    }
}
