package dev.xstreamka.ccsessions.core

/** Список id открытых вкладок (сессий или транскриптов): в порядке открытия, без дублей. */
object OpenTabsList {
    /** Добавить [id] в конец, если его ещё нет. true — список изменился. */
    fun add(list: MutableList<String>, id: String): Boolean {
        if (id.isBlank() || id in list) return false
        return list.add(id)
    }

    /** Убрать [id] (все вхождения). true — список изменился. */
    fun remove(list: MutableList<String>, id: String): Boolean = list.removeAll { it == id }
}
