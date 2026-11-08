package com.openproxy.client.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    private val locale = Locale("ru", "RU")

    fun bytes(value: Long): String {
        if (value <= 0L) return "0 Б"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var size = value.toDouble()
        var index = 0
        while (size >= 1024.0 && index < units.lastIndex) {
            size /= 1024.0
            index++
        }
        return if (index == 0) {
            "${size.toLong()} ${units[index]}"
        } else {
            String.format(locale, "%.1f %s", size, units[index])
        }
    }

    fun bytesPerSecond(value: Long): String = "${bytes(value)}/с"

    fun dateFromSeconds(seconds: Long): String {
        if (seconds <= 0L) return "—"
        val format = SimpleDateFormat("d MMMM yyyy", locale)
        return format.format(Date(seconds * 1000L))
    }

    fun date(millis: Long): String {
        if (millis <= 0L) return "—"
        return SimpleDateFormat("d MMMM yyyy, HH:mm", locale).format(Date(millis))
    }

    fun relativeTime(millis: Long): String {
        if (millis <= 0L) return "никогда"
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < 60_000L -> "только что"
            diff < 3_600_000L -> "${diff / 60_000L} мин назад"
            diff < 86_400_000L -> "${diff / 3_600_000L} ч назад"
            else -> "${diff / 86_400_000L} дн назад"
        }
    }

    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(locale, "%02d:%02d", minutes, seconds)
        }
    }

    fun daysLeft(days: Long): String = when {
        days < 0L -> "без ограничения"
        days == 0L -> "истекает сегодня"
        else -> "$days дн."
    }
}
