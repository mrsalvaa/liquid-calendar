package dev.liquidcalendar.core.ics

import net.fortuna.ical4j.model.Recur
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition

/**
 * Una zona definida solo dentro del archivo (TZID que no es IANA, con su VTIMEZONE). Calcula el
 * desfase de cualquier hora local a partir de sus observancias (STANDARD/DAYLIGHT), sin depender
 * de que la plataforma permita registrar zonas propias (Android no lo permite).
 */
class FileZone internal constructor(val tzid: String, private val observances: List<Observance>) {
    internal class Observance(val start: LocalDateTime, val from: ZoneOffset, val to: ZoneOffset, val rule: Recur<LocalDateTime>?, val rdates: List<LocalDateTime>)

    private val cache = HashMap<Int, List<Pair<LocalDateTime, ZoneOffset>>>()

    /** Cambios de desfase (hora local de inicio, desfase nuevo) que pueden afectar a [year]. */
    @Synchronized
    private fun transitionsAround(year: Int): List<Pair<LocalDateTime, ZoneOffset>> = cache.getOrPut(year) {
        val until = LocalDateTime.of(year + 1, 1, 2, 0, 0)
        observances.flatMap { o ->
            val onsets = buildList {
                add(o.start)
                addAll(o.rdates)
                if (o.rule != null) {
                    // Solo el último inicio anterior al año y los del año: no hace falta la historia entera.
                    val from = LocalDateTime.of(year - 1, 1, 1, 0, 0).coerceAtLeast(o.start)
                    try {
                        addAll(o.rule.getDates(o.start, from, until, 400))
                    } catch (e: Exception) {
                        // regla rota: quedan el inicio y los RDATE
                    }
                }
            }
            onsets.filter { it < until }.map { it to o.to }
        }.sortedBy { it.first }
    }

    /** Desfase de una hora local en esta zona. */
    fun offsetAt(local: LocalDateTime): ZoneOffset {
        val last = transitionsAround(local.year).lastOrNull { it.first <= local }
            ?: transitionsAround(local.year - 1).lastOrNull { it.first <= local }
        return last?.second ?: observances.minByOrNull { it.start }?.from ?: ZoneOffset.UTC
    }

    fun toInstant(local: LocalDateTime): Instant = local.toInstant(offsetAt(local))

    /** Hora local que corresponde a [instant] (para escribir un horario nuevo en esta zona). */
    fun toLocal(instant: Instant): LocalDateTime {
        // Se prueba con el desfase de la hora estimada y se corrige una vez (alcanza para cambios de 1 h).
        var local = LocalDateTime.ofInstant(instant, offsetAt(LocalDateTime.ofInstant(instant, ZoneOffset.UTC)))
        local = LocalDateTime.ofInstant(instant, offsetAt(local))
        return local
    }

    private fun LocalDateTime.coerceAtLeast(min: LocalDateTime) = if (this < min) min else this
}

/** VTIMEZONE a partir de las reglas de una zona IANA del sistema. */
object VTimezones {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAYS = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )

    /**
     * Líneas BEGIN:VTIMEZONE … END:VTIMEZONE con las reglas vigentes de [zone]: STANDARD y
     * DAYLIGHT con su RRULE anual si cambia de hora, o un único STANDARD si no.
     */
    fun of(zone: ZoneId, now: Instant = Instant.now()): List<String> {
        val rules = zone.rules
        val lines = mutableListOf("BEGIN:VTIMEZONE", "TZID:${zone.id}")
        val yearly = rules.transitionRules
        if (yearly.isEmpty()) {
            // Sin cambio de hora vigente: el desfase actual desde el último cambio (o desde 1970).
            val offset = rules.getOffset(now)
            val since = rules.previousTransition(now)?.let { LocalDateTime.ofInstant(it.instant, offset) } ?: LocalDateTime.of(1970, 1, 1, 0, 0)
            val before = rules.previousTransition(now)?.offsetBefore ?: offset
            lines += listOf(
                "BEGIN:STANDARD",
                "DTSTART:${LOCAL.format(since)}",
                "TZOFFSETFROM:${offset(before)}",
                "TZOFFSETTO:${offset(offset)}",
                "END:STANDARD",
            )
        } else {
            val baseYear = Year.of(1970)
            for (r in yearly) {
                val t = r.createTransition(baseYear.value)
                val daylight = t.offsetAfter.totalSeconds > r.standardOffset.totalSeconds
                val kind = if (daylight) "DAYLIGHT" else "STANDARD"
                lines += listOf(
                    "BEGIN:$kind",
                    "DTSTART:${LOCAL.format(t.dateTimeBefore)}",
                    "RRULE:${rrule(r)}",
                    "TZOFFSETFROM:${offset(t.offsetBefore)}",
                    "TZOFFSETTO:${offset(t.offsetAfter)}",
                    "END:$kind",
                )
            }
        }
        lines += "END:VTIMEZONE"
        return lines
    }

    /** RRULE anual equivalente a una regla de transición de java.time. */
    private fun rrule(r: ZoneOffsetTransitionRule): String {
        val month = r.month.value
        val dow = r.dayOfWeek
        val day = r.dayOfMonthIndicator
        val byDay = when {
            dow == null -> "BYMONTHDAY=$day"
            day == -1 -> "BYDAY=-1${DAYS[dow]}" // el último de ese día de la semana del mes
            day > 0 && (day - 1) % 7 == 0 -> "BYDAY=${(day - 1) / 7 + 1}${DAYS[dow]}" // el 1.º, 2.º... del mes
            day > 0 -> "BYMONTHDAY=${(day..day + 6).joinToString(",")};BYDAY=${DAYS[dow]}" // el primero desde el día N
            else -> "BYMONTHDAY=${(day - 6..day).joinToString(",")};BYDAY=${DAYS[dow]}" // el último hasta N días antes del fin
        }
        // La hora del RRULE es la del DTSTART (hora local anterior al cambio), que ya contempla
        // si la regla se define en UTC, hora estándar o hora local.
        if (r.timeDefinition != TimeDefinition.WALL && r.timeDefinition != TimeDefinition.STANDARD && r.timeDefinition != TimeDefinition.UTC) error("definición de hora desconocida")
        return "FREQ=YEARLY;BYMONTH=$month;$byDay"
    }

    private fun offset(o: ZoneOffset): String {
        val total = o.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        val s = abs % 60
        return "%s%02d%02d".format(sign, h, m) + if (s != 0) "%02d".format(s) else ""
    }
}
