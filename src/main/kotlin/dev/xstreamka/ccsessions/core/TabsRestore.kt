package dev.xstreamka.ccsessions.core

/**
 * Что вернуть из сохранённых списков вкладок при открытии проекта, а что из них убрать.
 * Проверки — снаружи (файлы, архив, live-статус), здесь только правила и порядок.
 */
object TabsRestore {
    /**
     * [restore] — открыть (в порядке списка), [drop] — убрать из сохранённого списка,
     * [keep] — сейчас не открывать, но оставить в списке (проверим снова при следующем открытии проекта).
     */
    data class Plan(val restore: List<String>, val drop: List<String>, val keep: List<String> = emptyList())

    /**
     * Вкладки сессий: восстанавливаем, только если транскрипт есть, сессия не в архиве и сейчас не работает
     * (её уже ведёт другой claude — второй `--resume` не запускаем). Удалённые, пустые и архивные — в [Plan.drop];
     * работающие — в [Plan.keep]: процесс мог оказаться убитым (падение IDE), и сессию нельзя терять из списка.
     * Сначала проверяется файл: для удалённой сессии нет сводки, остальные проверки по ней не вызываются.
     */
    fun sessions(
        ids: List<String>,
        exists: (String) -> Boolean,
        archived: (String) -> Boolean,
        active: (String) -> Boolean,
    ): Plan {
        val restore = ArrayList<String>()
        val drop = ArrayList<String>()
        val keep = ArrayList<String>()
        for (id in OpenTabsList.normalized(ids)) when {
            !exists(id) || archived(id) -> drop += id
            active(id) -> keep += id
            else -> restore += id
        }
        return Plan(restore, drop, keep)
    }

    /**
     * Сессию сейчас ведёт другой claude (вне этой IDE), и второй `--resume` запускать нельзя: live-статус активный
     * с учётом «зависших» ([LiveState.effectiveStatus] на момент [now]: давно молчащая «работает» — убитый процесс,
     * ожидание разрешения не «протухает») и последнее событие пришло позже закрытия проекта [closedAt] +
     * [CLOSE_GRACE_MS]. Наш claude, убитый вместе с IDE, обычно остаётся «работает» с событиями до закрытия — такую
     * сессию восстанавливаем; после падения IDE [closedAt] старый, и отличить убитый процесс помогает только давность.
     * [closedAt] = 0 (неизвестно) — по live-статусу не пропускаем.
     */
    fun runningElsewhere(state: LiveState, closedAt: Long, now: Long): Boolean =
        closedAt > 0 && state.effectiveStatus(now).active && state.updatedAt > closedAt + CLOSE_GRACE_MS

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
