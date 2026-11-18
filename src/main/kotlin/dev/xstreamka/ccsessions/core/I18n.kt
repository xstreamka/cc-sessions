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

    /** MessageFormat для всех строк, в т. ч. без параметров: апостроф в значениях пишется как `''`. */
    fun format(pattern: String, vararg args: Any?): String = MessageFormat(pattern, locale()).format(args)

    private fun bundle(locale: Locale): ResourceBundle =
        bundles.getOrPut(locale) { ResourceBundle.getBundle(BUNDLE, locale, I18n::class.java.classLoader, control) }
}
