package dev.liquidcalendar.data

import android.util.LruCache
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.IcsResource
import dev.liquidcalendar.core.ics.Occurrence
import dev.liquidcalendar.core.ics.Recurrence
import dev.liquidcalendar.core.ics.When
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Una ocurrencia con el calendario al que pertenece. */
data class AgendaItem(val occurrence: Occurrence, val calendar: CalendarInfo) {
    /** Días (en la zona del dispositivo) que ocupa, para ubicarlo en las vistas. */
    fun days(zone: ZoneId): ClosedRange<LocalDate> = when (val t = occurrence.time) {
        is When.AllDay -> t.first..t.endExclusive.minusDays(1)
        is When.Timed -> {
            val first = t.start.atZone(zone).toLocalDate()
            // Un evento que termina justo a medianoche no ocupa el día siguiente.
            val endZoned = t.end.atZone(zone)
            val last = if (t.end > t.start && endZoned.toLocalTime() == java.time.LocalTime.MIDNIGHT) endZoned.toLocalDate().minusDays(1) else endZoned.toLocalDate()
            first..maxOf(first, last)
        }
    }

    /** En curso en [now]: ya empezó y todavía no terminó (solo los que tienen hora). */
    fun isOngoing(now: java.time.Instant): Boolean {
        val t = occurrence.time
        return t is When.Timed && t.start <= now && now < t.end
    }
}

/**
 * Ocurrencias de un rango: se traen de la base solo los eventos que pueden caer en él y se
 * expanden las series únicamente para ese rango. Los archivos ya leídos se guardan en memoria
 * (por id y ETag), así que moverse entre meses no vuelve a interpretar cada .ics.
 */
object Agenda {
    private class Parsed(val tag: String, val resource: IcsResource?)

    private val cache = LruCache<Long, Parsed>(2000)

    /** Rangos ya calculados (ir y volver entre meses no recalcula nada); se vacía al cambiar la base. */
    private val ranges = LruCache<String, List<AgendaItem>>(24)
    @Volatile private var rangesVersion = -1L

    suspend fun load(from: Instant, to: Instant, zone: ZoneId): List<AgendaItem> = withContext(Dispatchers.Default) {
        val version = Repo.version.value
        if (version != rangesVersion) {
            ranges.evictAll()
            rangesVersion = version
        }
        val key = "$from|$to|$zone"
        ranges.get(key)?.let { return@withContext it }
        compute(from, to, zone).also { ranges.put(key, it) }
    }

    private suspend fun compute(from: Instant, to: Instant, zone: ZoneId): List<AgendaItem> {
        val (candidates, calendars) = withContext(Dispatchers.IO) {
            Repo.candidates(from.toEpochMilli(), to.toEpochMilli()) to Repo.calendarInfos()
        }
        return candidates.flatMap { c ->
            val calendar = calendars[c.calendarId] ?: return@flatMap emptyList()
            val resource = resource(c) ?: return@flatMap emptyList()
            Recurrence.expand(resource, from, to, zone).map { AgendaItem(it, calendar) }
        }.sortedWith(
            compareBy<AgendaItem>(
                // Primero los de todo el día, después por hora de inicio.
                { it.occurrence.time !is When.AllDay },
                {
                    when (val t = it.occurrence.time) {
                        is When.AllDay -> t.first.atStartOfDay(zone).toInstant()
                        is When.Timed -> t.start
                    }
                },
                { it.occurrence.summary.orEmpty() },
            ),
        )
    }

    private fun resource(c: Repo.Candidate): IcsResource? {
        // Los locales no tienen ETag: se usa el contenido como versión.
        val tag = c.etag ?: c.ics.hashCode().toString()
        cache.get(c.id)?.let { if (it.tag == tag) return it.resource }
        val parsed = try {
            Ics.parseResource(c.ics)
        } catch (e: IcsException) {
            null
        }
        cache.put(c.id, Parsed(tag, parsed))
        return parsed
    }
}
