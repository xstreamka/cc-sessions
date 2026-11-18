package dev.xstreamka.ccsessions.core

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/**
 * Тексты плагина из `messages/CcSessionsBundle*.properties`. Без зависимостей от IntelliJ: ядро тестируется без IDE,
 * а текущую локаль подставляет IDE-слой через [localeProvider].
 */
object I18n {
    const val BUNDLE = "messages.CcSessionsBundle"

    /** Откуда брать текущую локаль; IDE-слой подставляет свою. По умолчанию — английский (Locale.ROOT). */
    @Volatile var localeProvider: () -> Locale = { Locale.ROOT }

    /** Без отката к локали JVM: при [Locale.ROOT] всегда базовый (английский) файл, даже на русской системе. */
    private val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
    private val bundles = ConcurrentHashMap<Locale, ResourceBundle>()

    fun locale(): Locale = localeProvider()

    /** Строка по ключу в текущей локали; [args] — через MessageFormat. Нет ключа — вернуть сам ключ (не падать). */
    fun msg(key: String, vararg args: Any?): String {
        val pattern = try {
            bundle(locale()).getString(key)
        } catch (_: MissingResourceException) {
            return key
        }
        return format(pattern, *args)
    }

    /**
     * Как BundleBase платформы (одно правило апострофа и для ключей, которые платформа читает из plugin.xml сама):
     * без [args] — значение как есть (обычный `'`), с [args] — MessageFormat (апостроф `''`).
     * Сломанный шаблон — вернуть его как есть, не падать.
     */
    fun format(pattern: String, vararg args: Any?): String {
        if (args.isEmpty()) return pattern
        return try {
            MessageFormat(pattern, locale()).format(args)
        } catch (_: IllegalArgumentException) {
            pattern
        }
    }

    private fun bundle(locale: Locale): ResourceBundle =
        bundles.getOrPut(locale) { ResourceBundle.getBundle(BUNDLE, locale, I18n::class.java.classLoader, control) }
}
