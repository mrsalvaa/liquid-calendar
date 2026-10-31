package dev.liquidcalendar.core.ics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Cada cuánto se repite. */
enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** Hasta cuándo se repite. */
sealed interface RepeatEnd {
    data object Never : RepeatEnd

    /** Hasta ese día inclusive (en la hora local). */
    data class Until(val date: LocalDate) : RepeatEnd

    /** [times] ocurrencias en total, contando la primera. */
    data class Count(val times: Int) : RepeatEnd
}

/**
 * Una regla que el editor sabe mostrar y escribir. Lo que no entra en este modelo (BYSETPOS,
 * BYHOUR, varias reglas...) es [Repetition.Custom] y se conserva tal cual.
 *
 * - [weekdays]: solo en semanal; nunca vacío.
 * - [nthWeekday]: solo en mensual. null = el mismo día del mes que el inicio; si no, "el n-ésimo
 *   [DayOfWeek] del mes" (1 a 4, o -1 = el último).
 */
data class RepeatRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val nthWeekday: Pair<Int, DayOfWeek>? = null,
    val end: RepeatEnd = RepeatEnd.Never,
) {
    init {
        require(interval >= 1) { "El intervalo es al menos 1" }
        require(frequency != Frequency.WEEKLY || weekdays.isNotEmpty()) { "Una regla semanal necesita algún día" }
        require(end !is RepeatEnd.Count || end.times >= 1) { "La cantidad es al menos 1" }
    }
}

/** La repetición de un evento, como la ve el formulario. */
sealed interface Repetition {
    data object None : Repetition

    data class Rule(val rule: RepeatRule) : Repetition

    /** Una regla de otro cliente que el editor no sabe mostrar: no se toca mientras no se cambie. */
    data class Custom(val rrule: String) : Repetition
}

object Repeats {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    private val CODES = DAYS.entries.associate { (k, v) -> v to k }

    /** La repetición del evento principal [uid] de [resource]. */
    fun of(resource: IcsResource, uid: String, zone: ZoneId): Repetition {
        val c = resource.components.firstOrNull { it.info.uid == uid && it.recurrenceId == null } ?: return Repetition.None
        return when {
            c.ruleTexts.isEmpty() -> Repetition.None
            c.ruleTexts.size > 1 -> Repetition.Custom(c.ruleTexts.joinToString("\n"))
            else -> parse(c.ruleTexts.first(), startDate(c, zone), zone)
        }
    }

    private fun startDate(c: EventComponent, zone: ZoneId): LocalDate = when (val s = c.start) {
        is LocalDate -> s
        is java.time.ZonedDateTime -> s.withZoneSameInstant(zone).toLocalDate()
        is LocalDateTime -> s.toLocalDate()
        else -> LocalDate.now(zone)
    }

    /**
     * Lee el valor de una RRULE ("FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=..."). [start] es el día de inicio
     * del evento: una regla mensual con BYMONTHDAY de ese mismo día es la de siempre.
     */
    fun parse(value: String, start: LocalDate, zone: ZoneId): Repetition {
        val custom = Repetition.Custom(value)
        val parts = value.split(';').filter { '=' in it }.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        val known = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "COUNT", "UNTIL", "WKST")
        if (parts.keys.any { it !in known }) return custom
        val frequency = when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> Frequency.DAILY
            "WEEKLY" -> Frequency.WEEKLY
            "MONTHLY" -> Frequency.MONTHLY
            "YEARLY" -> Frequency.YEARLY
            else -> return custom
        }
        val interval = parts["INTERVAL"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return custom } ?: 1
        val end = when {
            parts["COUNT"] != null && parts["UNTIL"] != null -> return custom
            parts["COUNT"] != null -> RepeatEnd.Count(parts["COUNT"]!!.toIntOrNull()?.takeIf { it >= 1 } ?: return custom)
            parts["UNTIL"] != null -> RepeatEnd.Until(untilDate(parts["UNTIL"]!!, zone) ?: return custom)
            else -> RepeatEnd.Never
        }
        val byDay = parts["BYDAY"]?.split(',')?.map { it.trim().uppercase() }
        val byMonthDay = parts["BYMONTHDAY"]
        val byMonth = parts["BYMONTH"]
        return when (frequency) {
            Frequency.DAILY -> if (byDay != null || byMonthDay != null || byMonth != null) custom else Repetition.Rule(RepeatRule(frequency, interval, end = end))
            Frequency.WEEKLY -> {
                if (byMonthDay != null || byMonth != null) return custom
                val days = byDay?.map { DAYS[it] ?: return custom }?.toSet() ?: setOf(start.dayOfWeek)
                Repetition.Rule(RepeatRule(frequency, interval, weekdays = days, end = end))
            }
            Frequency.MONTHLY -> {
                if (byMonth != null) return custom
                when {
                    byDay != null && byMonthDay != null -> custom
                    byDay != null -> {
                        val nth = byDay.singleOrNull()?.let { nthOf(it) } ?: return custom
                        Repetition.Rule(RepeatRule(frequency, interval, nthWeekday = nth, end = end))
                    }
                    byMonthDay != null && byMonthDay.toIntOrNull() != start.dayOfMonth -> custom
                    else -> Repetition.Rule(RepeatRule(frequency, interval, end = end))
                }
            }
            Frequency.YEARLY -> {
                if (byDay != null) return custom
                if (byMonth != null && byMonth.toIntOrNull() != start.monthValue) return custom
                if (byMonthDay != null && byMonthDay.toIntOrNull() != start.dayOfMonth) return custom
                Repetition.Rule(RepeatRule(frequency, interval, end = end))
            }
        }
    }

    /** "2TU" → (2, martes); "-1FR" → (-1, viernes). Solo 1 a 4 y −1. */
    private fun nthOf(code: String): Pair<Int, DayOfWeek>? {
        val day = DAYS[code.takeLast(2)] ?: return null
        val n = code.dropLast(2).removePrefix("+").toIntOrNull() ?: return null
        return if (n in 1..4 || n == -1) n to day else null
    }

    private fun untilDate(value: String, zone: ZoneId): LocalDate? = try {
        when {
            value.length == 8 -> LocalDate.parse(value, DATE)
            value.endsWith("Z") -> LocalDateTime.parse(value.removeSuffix("Z"), LOCAL).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
            else -> LocalDateTime.parse(value, LOCAL).toLocalDate()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * El valor de la RRULE (sin "RRULE:"). [allDay]: el UNTIL va como fecha; si no, como el último
     * segundo de ese día en [zone], en UTC ([floating] = sin zona, en hora local), como pide RFC 5545
     * según el tipo de DTSTART.
     */
    fun format(rule: RepeatRule, allDay: Boolean, zone: ZoneId, floating: Boolean = false): String {
        val parts = mutableListOf("FREQ=${rule.frequency.name}")
        if (rule.interval > 1) parts += "INTERVAL=${rule.interval}"
        when (rule.frequency) {
            Frequency.WEEKLY -> parts += "BYDAY=" + rule.weekdays.sortedBy { it.value }.joinToString(",") { CODES[it]!! }
            Frequency.MONTHLY -> rule.nthWeekday?.let { (n, day) -> parts += "BYDAY=$n${CODES[day]}" }
            else -> Unit
        }
        when (val e = rule.end) {
            RepeatEnd.Never -> Unit
            is RepeatEnd.Count -> parts += "COUNT=${e.times}"
            is RepeatEnd.Until -> parts += "UNTIL=" + when {
                allDay -> DATE.format(e.date)
                floating -> LOCAL.format(e.date.atTime(23, 59, 59))
                else -> UTC.format(e.date.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1))
            }
        }
        return parts.joinToString(";")
    }

    /** La regla de siempre para [frequency] empezando en [start]: semanal ese día, mensual ese número. */
    fun simple(frequency: Frequency, start: LocalDate): RepeatRule = RepeatRule(
        frequency,
        weekdays = if (frequency == Frequency.WEEKLY) setOf(start.dayOfWeek) else emptySet(),
    )

    /** "El n-ésimo día de la semana" de [date]: 1 a 4, o 5 si es el quinto (que se escribe como último). */
    fun nthOfMonth(date: LocalDate): Int = (date.dayOfMonth - 1) / 7 + 1

    /** true si [date] cae en la última semana de su mes (no hay otro igual después). */
    fun isLastOfMonth(date: LocalDate): Boolean =
        date.with(TemporalAdjusters.lastInMonth(date.dayOfWeek)) == date

    /**
     * La regla ajustada a un inicio nuevo: si se repetía el día de la semana del inicio anterior (o
     * "el n-ésimo de ese día"), pasa a ser el del inicio nuevo. Lo que el usuario eligió aparte
     * (varios días, por ejemplo) no se toca.
     */
    fun follow(rule: RepeatRule, oldStart: LocalDate, newStart: LocalDate): RepeatRule = when {
        oldStart == newStart -> rule
        rule.frequency == Frequency.WEEKLY && rule.weekdays == setOf(oldStart.dayOfWeek) -> rule.copy(weekdays = setOf(newStart.dayOfWeek))
        rule.frequency == Frequency.MONTHLY && rule.nthWeekday != null -> {
            // "El último" sigue siéndolo si el día nuevo también es el último del mes; si no, pasa a
            // "el n-ésimo" (la regla tiene que incluir el día en que empieza).
            val wasLast = rule.nthWeekday.first == -1
            val n = if (wasLast && isLastOfMonth(newStart)) -1 else nthOfMonth(newStart).let { if (it == 5) -1 else it }
            rule.copy(nthWeekday = n to newStart.dayOfWeek)
        }
        else -> rule
    }
}
