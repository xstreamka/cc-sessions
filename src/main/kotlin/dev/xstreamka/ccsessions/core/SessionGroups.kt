package dev.xstreamka.ccsessions.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DayGroup(val title: String) {
    FAVORITES("Избранное"), TODAY("Сегодня"), YESTERDAY("Вчера"), THIS_WEEK("На этой неделе"), EARLIER("Ранее")
}

object SessionGroups {
    /** Группа для сессии: избранные — FAVORITES; иначе по дате updatedAt в зоне [zone]. null updatedAt → EARLIER. */
    fun groupOf(updatedAt: Instant?, favorite: Boolean, today: LocalDate, zone: ZoneId): DayGroup {
        if (favorite) return DayGroup.FAVORITES
        if (updatedAt == null) return DayGroup.EARLIER
        val date = updatedAt.atZone(zone).toLocalDate()
        return when {
            date >= today -> DayGroup.TODAY
            date == today.minusDays(1) -> DayGroup.YESTERDAY
            date >= today.with(DayOfWeek.MONDAY) -> DayGroup.THIS_WEEK
            else -> DayGroup.EARLIER
        }
    }

    /** Раскладывает элементы по группам в порядке enum; пустые группы не возвращает; порядок элементов внутри группы сохраняется. */
    fun <T> group(items: List<T>, groupOf: (T) -> DayGroup): List<Pair<DayGroup, List<T>>> {
        val by = items.groupBy(groupOf)
        return DayGroup.values().mapNotNull { g -> by[g]?.let { g to it } }
    }
}
