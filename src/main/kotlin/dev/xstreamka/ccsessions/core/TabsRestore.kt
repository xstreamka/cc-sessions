package dev.xstreamka.ccsessions.core

/**
 * Что вернуть из сохранённых списков вкладок при открытии проекта, а что из них убрать.
 * Проверки — снаружи (файлы, архив, live-статус), здесь только правила и порядок.
 */
object TabsRestore {
    /** [restore] — открыть (в порядке списка), [drop] — убрать из сохранённого списка. */
    data class Plan(val restore: List<String>, val drop: List<String>)

    /**
     * Вкладки сессий: восстанавливаем, только если транскрипт есть, сессия не в архиве и сейчас не работает
     * (её уже ведёт другой claude — второй `--resume` не запускаем). Сначала проверяется файл: для удалённой
     * сессии нет сводки, остальные проверки по ней не вызываются.
     */
    fun sessions(
        ids: List<String>,
        exists: (String) -> Boolean,
        archived: (String) -> Boolean,
        active: (String) -> Boolean,
    ): Plan = split(ids) { exists(it) && !archived(it) && !active(it) }

    /** Вкладки транскриптов: только по наличию файла (транскрипт архивной или работающей сессии открыть можно). */
    fun transcripts(ids: List<String>, exists: (String) -> Boolean): Plan = split(ids, exists)

    private fun split(ids: List<String>, keep: (String) -> Boolean): Plan {
        val restore = ArrayList<String>()
        val drop = ArrayList<String>()
        for (id in OpenTabsList.normalized(ids)) if (keep(id)) restore += id else drop += id
        return Plan(restore, drop)
    }
}
