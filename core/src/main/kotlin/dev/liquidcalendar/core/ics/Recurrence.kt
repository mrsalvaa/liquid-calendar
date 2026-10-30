package dev.liquidcalendar.core.ics

import net.fortuna.ical4j.model.Recur
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal

/** Cuándo ocurre algo: días completos (sin zona, nunca se corren) o un intervalo exacto. */
sealed interface When {
    /** [first] incluido, [endExclusive] excluido: un evento de un día tiene endExclusive = first + 1. */
    data class AllDay(val first: LocalDate, val endExclusive: LocalDate) : When

    data class Timed(val start: Instant, val end: Instant) : When
}

/** Una ocurrencia concreta de un evento, lista para mostrar. */
data class Occurrence(
    val uid: String,
    val summary: String?,
    val location: String?,
    val description: String?,
    val time: When,
    /** Pertenece a una serie (o es una excepción de una). */
    val recurring: Boolean,
    /**
     * Identifica esta ocurrencia dentro de la serie: el inicio original (el RECURRENCE-ID que
     * tendría o tiene). Estable aunque la ocurrencia se haya movido.
     */
    val key: String,
    /** Recordatorios de esta ocurrencia (los de su componente). */
    val alarms: List<AlarmSpec> = emptyList(),
    /** Inicio original (lo que sería su RECURRENCE-ID), para editar o borrar solo esta. */
    val original: Moment? = null,
)

/** Cota del tiempo que puede ocupar un recurso, para filtrarlo en la base antes de expandirlo. */
data class Bounds(val start: Instant, val end: Instant?)

/**
 * Expande series (RRULE, RDATE, EXDATE y excepciones con RECURRENCE-ID) solo dentro del rango
 * pedido. Los eventos de todo el día se tratan como fechas, nunca como instantes, así que no
 * cambian de día según la zona del dispositivo; las horas flotantes se interpretan en [zone].
 */
object Recurrence {
    /** Tope de ocurrencias por serie y por rango: una regla absurda no puede trabar la app. */
    const val MAX_PER_SERIES = 2000

    /** Margen para fechas sin zona: cualquier zona del mundo está a menos de 14 h de UTC. */
    private val SLACK: Duration = Duration.ofHours(14)

    /**
     * [fastForward] (siempre en la app; los tests lo comparan con false) adelanta el inicio de las
     * series sin COUNT hasta cerca del rango, para no recorrer años de ocurrencias pasadas.
     */
    fun expand(resource: IcsResource, from: Instant, to: Instant, zone: ZoneId, fastForward: Boolean = true): List<Occurrence> {
        if (!from.isBefore(to)) return emptyList()
        val masters = resource.components.filter { it.recurrenceId == null }
        val overrides = resource.components.filter { it.recurrenceId != null }
        val result = mutableListOf<Occurrence>()
        for (m in masters) {
            // Las ocurrencias reemplazadas por una excepción no se generan desde la serie.
            val fz = m.localZone
            val replaced = overrides.filter { it.info.uid == m.info.uid }.mapNotNull { o -> o.recurrenceId?.let { identity(it, zone, o.localZone) } }.toSet()
            val excluded = m.exdates.map { identity(it, zone, fz) }.toSet() + replaced
            for (start in starts(m, from, to, zone, fastForward)) {
                if (identity(start, zone, fz) in excluded) continue
                val time = window(start, m.length, zone, fz)
                if (overlaps(time, from, to, zone)) result += occurrence(m, time, start, zone, m.rrules.isNotEmpty() || m.rdates.isNotEmpty())
            }
        }
        for (o in overrides) {
            val time = window(o.start, o.length, zone, o.localZone)
            if (overlaps(time, from, to, zone)) result += occurrence(o, time, o.recurrenceId!!, zone, recurring = true)
        }
        return result.sortedWith(compareBy({ sortKey(it.time, zone) }, { it.summary }))
    }

    /** Dónde puede caer el recurso: null en [Bounds.end] = sin fin (serie sin UNTIL ni COUNT). */
    fun bounds(resource: IcsResource): Bounds? {
        if (resource.components.isEmpty()) return null
        var start: Instant? = null
        var end: Instant? = null
        var infinite = false
        val utc = ZoneId.of("UTC")
        for (c in resource.components) {
            val s = approx(c.start, utc, c.localZone).minus(SLACK)
            if (start == null || s < start) start = s
            val lastStart: Temporal? = when {
                c.recurrenceId != null || (c.rrules.isEmpty() && c.rdates.isEmpty()) -> c.start
                c.rrules.any { it.until == null && it.count < 1 } -> {
                    infinite = true
                    null
                }
                else -> {
                    val fromRules = c.rrules.mapNotNull { r -> lastOf(r, c.start) }
                    (fromRules + c.rdates + c.start).maxByOrNull { approx(it, utc, c.localZone) }
                }
            }
            if (lastStart != null) {
                val e = approx(lastStart, utc, c.localZone).plus(lengthOf(c.length)).plus(SLACK)
                if (end == null || e > end) end = e
            }
        }
        return Bounds(start!!, if (infinite) null else end)
    }

    // --- Inicios de cada ocurrencia de una serie dentro del rango ---

    private fun starts(c: EventComponent, from: Instant, to: Instant, zone: ZoneId, fastForward: Boolean): List<Temporal> {
        if (c.rrules.isEmpty() && c.rdates.isEmpty()) return listOf(c.start)
        // Una ocurrencia que empezó antes del rango puede seguir dentro: se amplía hacia atrás.
        val windowStart = from.minus(lengthOf(c.length)).minus(SLACK)
        val windowEnd = to.plus(SLACK)
        val fz = c.localZone
        val (a, b) = bounds(c.start, windowStart, windowEnd, zone, fz)
        val out = LinkedHashMap<Any, Temporal>()
        // El DTSTART es siempre la primera ocurrencia (RFC 5545), lo diga o no la regla.
        if (inRange(c.start, windowStart, windowEnd, zone, fz)) out[identity(c.start, zone, fz)] = c.start
        for (rule in c.rrules) {
            val dates = try {
                val seed = if (fastForward) fastSeed(rule, c.start, a) else c.start
                rule.getDates(seed, a, b, MAX_PER_SERIES)
            } catch (e: Exception) {
                emptyList()
            }
            for (d in dates) out.putIfAbsent(identity(d, zone, fz), d)
        }
        for (d in c.rdates) if (inRange(d, windowStart, windowEnd, zone, fz)) out.putIfAbsent(identity(d, zone, fz), d)
        return out.values.take(MAX_PER_SERIES)
    }

    /**
     * Un inicio equivalente más cercano a [windowStart]: avanzar períodos enteros de la regla (días,
     * semanas, meses o años, respetando INTERVAL) no cambia qué fechas genera. No se hace si hay
     * COUNT (hay que contar desde el principio) ni en reglas mensuales o anuales que empiezan
     * después del día 28 (sumar meses cambiaría el día). Se deja un período de margen.
     */
    private fun fastSeed(rule: Recur<Temporal>, seed: Temporal, windowStart: Temporal): Temporal {
        if (rule.count >= 1) return seed
        val unit = when (rule.frequency?.name) {
            "DAILY" -> java.time.temporal.ChronoUnit.DAYS
            "WEEKLY" -> java.time.temporal.ChronoUnit.WEEKS
            "MONTHLY" -> java.time.temporal.ChronoUnit.MONTHS
            "YEARLY" -> java.time.temporal.ChronoUnit.YEARS
            else -> return seed
        }
        val dayOfMonth = when (seed) {
            is LocalDate -> seed.dayOfMonth
            is ZonedDateTime -> seed.dayOfMonth
            is LocalDateTime -> seed.dayOfMonth
            else -> return seed
        }
        if (unit >= java.time.temporal.ChronoUnit.MONTHS && dayOfMonth > 28) return seed
        val interval = rule.interval.coerceAtLeast(1).toLong()
        val periods = try {
            unit.between(seed, windowStart) / interval - 1
        } catch (e: Exception) {
            return seed
        }
        if (periods <= 0) return seed
        return seed.plus(periods * interval, unit)
    }

    /** Extremos del rango expresados en el mismo tipo que el inicio de la serie. */
    private fun bounds(seed: Temporal, from: Instant, to: Instant, zone: ZoneId, fz: FileZone? = null): Pair<Temporal, Temporal> = when (seed) {
        is LocalDate -> from.atZone(zone).toLocalDate().minusDays(1) to to.atZone(zone).toLocalDate().plusDays(1)
        is ZonedDateTime -> from.atZone(seed.zone) to to.atZone(seed.zone)
        else -> if (fz != null) fz.toLocal(from) to fz.toLocal(to) else from.atZone(zone).toLocalDateTime() to to.atZone(zone).toLocalDateTime()
    }

    private fun lastOf(rule: Recur<Temporal>, seed: Temporal): Temporal? = try {
        val farEnd: Temporal = when (seed) {
            is LocalDate -> seed.plusYears(200)
            is ZonedDateTime -> seed.plusYears(200)
            else -> (seed as LocalDateTime).plusYears(200)
        }
        rule.getDates(seed, seed, farEnd, MAX_PER_SERIES * 50).lastOrNull()
    } catch (e: Exception) {
        null
    }

    // --- Conversión de fechas ---

    private fun window(start: Temporal, length: Length, zone: ZoneId, fz: FileZone? = null): When = when (start) {
        is LocalDate -> When.AllDay(start, start.plusDays((length as? Length.Days)?.days ?: 1))
        is ZonedDateTime -> start.toInstant().let { When.Timed(it, it.plus(lengthOf(length))) }
        else -> localInstant(start as LocalDateTime, zone, fz).let { When.Timed(it, it.plus(lengthOf(length))) }
    }

    private fun lengthOf(length: Length): Duration = when (length) {
        is Length.Days -> Duration.ofDays(length.days)
        is Length.Exact -> length.duration
    }

    private fun overlaps(time: When, from: Instant, to: Instant, zone: ZoneId): Boolean = when (time) {
        is When.Timed -> time.start < to && (time.end > from || (time.end == time.start && time.start >= from))
        is When.AllDay -> {
            val fromDate = from.atZone(zone).toLocalDate()
            // "to" es excluyente: si cae justo a medianoche, ese día ya no está en el rango.
            val toZoned = to.atZone(zone)
            val lastDate = if (toZoned.toLocalTime() == java.time.LocalTime.MIDNIGHT) toZoned.toLocalDate().minusDays(1) else toZoned.toLocalDate()
            time.first <= lastDate && time.endExclusive > fromDate
        }
    }

    private fun inRange(t: Temporal, from: Instant, to: Instant, zone: ZoneId, fz: FileZone? = null): Boolean {
        val i = approx(t, zone, fz)
        return i >= from && i < to
    }

    /** Instante aproximado (las fechas sin zona se toman en [zone]). */
    private fun approx(t: Temporal, zone: ZoneId, fz: FileZone? = null): Instant = when (t) {
        is LocalDate -> t.atStartOfDay(zone).toInstant()
        is ZonedDateTime -> t.toInstant()
        else -> localInstant(t as LocalDateTime, zone, fz)
    }

    /** Para comparar ocurrencias (EXDATE, RECURRENCE-ID) sin depender de la zona en que se escribió. */
    private fun identity(t: Temporal, zone: ZoneId, fz: FileZone? = null): Any = when (t) {
        is LocalDate -> t
        is ZonedDateTime -> t.toInstant()
        else -> localInstant(t as LocalDateTime, zone, fz)
    }

    /** Hora local → instante: con la zona propia del archivo si la hay; si no (flotante), la del dispositivo. */
    private fun localInstant(t: LocalDateTime, zone: ZoneId, fz: FileZone?): Instant = fz?.toInstant(t) ?: t.atZone(zone).toInstant()

    private fun sortKey(time: When, zone: ZoneId): Instant = when (time) {
        is When.AllDay -> time.first.atStartOfDay(zone).toInstant()
        is When.Timed -> time.start
    }

    private fun occurrence(c: EventComponent, time: When, originalStart: Temporal, zone: ZoneId, recurring: Boolean, fz: FileZone? = c.localZone) = Occurrence(
        uid = c.info.uid,
        summary = c.info.summary,
        location = c.info.location,
        description = c.info.description,
        time = time,
        recurring = recurring,
        key = "${c.info.uid}|${identity(originalStart, zone, fz)}",
        alarms = c.alarms,
        original = when (val id = identity(originalStart, zone, fz)) {
            is java.time.LocalDate -> Moment.Date(id)
            is Instant -> Moment.Exact(id)
            else -> null
        },
    )
}
