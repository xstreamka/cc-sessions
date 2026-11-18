package dev.xstreamka.ccsessions.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Короткие подписи времени для списка сессий («сегодня 10:05», «5 мин назад», «3 мин») в языке плагина. */
object RelativeTime {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    /** Когда была активность [ts]: только что / N мин назад / сегодня ЧЧ:ММ / вчера ЧЧ:ММ / дата. */
    fun relative(ts: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Duration.between(ts, now)
        val day = ts.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            d.toMinutes() < 1 -> I18n.msg("time.justNow")
            d.toMinutes() < 60 -> I18n.msg("time.minutesAgo", d.toMinutes())
            day == today -> I18n.msg("time.today", timeFmt.format(ts.atZone(zone)))
            day == today.minusDays(1) -> I18n.msg("time.yesterday", timeFmt.format(ts.atZone(zone)))
            else -> DateTimeFormatter.ofPattern(I18n.msg("time.datePattern"), I18n.formatLocale()).format(ts.atZone(zone))
        }
    }

    /** Длительность одним числом: секунды, минуты или часы. */
    fun short(d: Duration): String = when {
        d.seconds < 60 -> I18n.msg("duration.seconds", d.seconds)
        d.toMinutes() < 60 -> I18n.msg("duration.minutes", d.toMinutes())
        else -> I18n.msg("duration.hours", d.toHours())
    }
}
