package dev.xstreamka.ccsessions.core

/**
 * Список id открытых вкладок (сессий или транскриптов): в порядке открытия, без дублей.
 * Функции не меняют входной список, а возвращают новый (тот же объект, если ничего не изменилось):
 * текущий список в это время может читать сериализация состояния в фоне.
 */
object OpenTabsList {
    /** [list] с [id] в конце, если его ещё нет; пустой id не учитывается. */
    fun added(list: List<String>, id: String): List<String> =
        if (id.isBlank() || id in list) list else list + id

    /** [list] без [id] (всех вхождений). */
    fun removed(list: List<String>, id: String): List<String> =
        if (id !in list) list else list.filter { it != id }

    /** Привести загруженный список к виду «без пустых и дублей», порядок первых вхождений сохраняется. */
    fun normalized(list: List<String>): List<String> = list.filter { it.isNotBlank() }.distinct()
}
