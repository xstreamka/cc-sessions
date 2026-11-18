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

    /**
     * Сессию сейчас ведёт другой claude (вне этой IDE), и второй `--resume` запускать нельзя: сырой live-статус
     * активный ([statusActive] — именно `status.active`, а не effectiveStatus: ожидание разрешения не «протухает»)
     * и последнее событие пришло позже закрытия проекта [closedAt] + [CLOSE_GRACE_MS]. Наш claude, убитый вместе
     * с IDE, обычно остаётся «работает» с событиями до закрытия — такую сессию восстанавливаем.
     * [closedAt] = 0 (неизвестно) — по live-статусу не пропускаем.
     */
    fun runningElsewhere(statusActive: Boolean, updatedAt: Long, closedAt: Long): Boolean =
        closedAt > 0 && statusActive && updatedAt > closedAt + CLOSE_GRACE_MS

    /** Пока IDE выходит, наш claude ещё успевает писать события — их не считаем чужой работой. */
    const val CLOSE_GRACE_MS = 20_000L

    /** Вкладки транскриптов: только по наличию файла (транскрипт архивной или работающей сессии открыть можно). */
    fun transcripts(ids: List<String>, exists: (String) -> Boolean): Plan = split(ids, exists)

    private fun split(ids: List<String>, keep: (String) -> Boolean): Plan {
        val restore = ArrayList<String>()
        val drop = ArrayList<String>()
        for (id in OpenTabsList.normalized(ids)) if (keep(id)) restore += id else drop += id
        return Plan(restore, drop)
    }
}
