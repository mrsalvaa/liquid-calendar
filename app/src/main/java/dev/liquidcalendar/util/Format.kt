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
