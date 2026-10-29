package dev.liquidcalendar.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Fechas siempre en día/mes/año, sin opción para cambiarlo. Los nombres de días y meses siguen
// el idioma del teléfono.

private val numericDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun String.capitalized(locale: Locale): String = replaceFirstChar { it.titlecase(locale) }

/** "28/10/2026". */
fun numericDate(date: LocalDate): String = numericDate.format(date)

/** "Miércoles". */
fun dayName(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
    day.getDisplayName(TextStyle.FULL, locale).capitalized(locale)

/** "Octubre 2026". */
fun monthTitle(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    "${date.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).capitalized(locale)} ${date.year}"

/** "Miércoles 28/10/2026". */
fun longDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    "${dayName(date.dayOfWeek, locale)} ${numericDate(date)}"

/** "29/10". */
fun shortDate(date: LocalDate): String = DateTimeFormatter.ofPattern("dd/MM").format(date)

/** "26/10 – 01/11/2026": la semana que empieza en [start]. */
fun weekTitle(start: LocalDate): String = "${shortDate(start)} – ${numericDate(start.plusDays(6))}"

/** Inicial del día para las cabeceras ("L", "M"...). */
fun dayInitial(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
    day.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)

/** "Jue". */
fun dayShort(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
    day.getDisplayName(TextStyle.SHORT_STANDALONE, locale).removeSuffix(".").replaceFirstChar { it.titlecase(locale) }

/** "10:00" o "10:00 a. m.", según la preferencia de 24 h del celu. */
fun timeOf(time: java.time.LocalTime): String {
    val is24 = android.text.format.DateFormat.is24HourFormat(Strings.app)
    return DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a").format(time)
}

/** "5" o "05" para la columna de horas de las vistas de semana y día. */
fun hourLabel(hour: Int): String {
    val is24 = android.text.format.DateFormat.is24HourFormat(Strings.app)
    return if (is24) "%02d".format(hour) else "${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "a. m." else "p. m."}"
}
