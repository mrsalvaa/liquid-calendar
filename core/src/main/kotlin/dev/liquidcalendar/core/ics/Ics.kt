package dev.liquidcalendar.core.ics

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.model.property.RRule
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal

/** Inicio o fin de un evento: un día completo, o un instante con la zona en que se definió. */
sealed interface EventTime {
    data class AllDay(val date: LocalDate) : EventTime

    /** [zone] null = hora "flotante" (sin zona: vale la hora local del dispositivo). */
    data class At(val dateTime: LocalDateTime, val zone: ZoneId?) : EventTime {
        fun toInstant(deviceZone: ZoneId): Instant = dateTime.atZone(zone ?: deviceZone).toInstant()
    }
}

/** Lo necesario de un VEVENT para la caché y las vistas. */
data class EventInfo(
    val uid: String,
    val summary: String?,
    val location: String?,
    val description: String?,
    val start: EventTime,
    val end: EventTime?,
    /** Tiene RRULE o RDATE. */
    val recurring: Boolean,
    /** Excepción de una serie (RECURRENCE-ID), o null si es el evento principal. */
    val recurrenceId: String?,
)

/**
 * Un VEVENT listo para expandir. Las fechas se guardan normalizadas: [LocalDate] (todo el día),
 * [ZonedDateTime] (con zona; las zonas IANA con las reglas del sistema) o [LocalDateTime]
 * (flotante). Nunca Instant ni OffsetDateTime: se convierten a ZonedDateTime.
 */
class EventComponent internal constructor(
    val info: EventInfo,
    internal val start: Temporal,
    /** Duración de cada ocurrencia: días para todo el día, tiempo exacto para el resto. */
    internal val length: Length,
    internal val rrules: List<Recur<Temporal>>,
    internal val rdates: List<Temporal>,
    internal val exdates: List<Temporal>,
    internal val recurrenceId: Temporal?,
) {
    val allDay: Boolean get() = start is LocalDate
}

internal sealed interface Length {
    data class Days(val days: Long) : Length
    data class Exact(val duration: Duration) : Length
}

/** Todos los VEVENT de un recurso iCalendar (la serie y sus excepciones comparten UID). */
class IcsResource internal constructor(val components: List<EventComponent>) {
    val uid: String? get() = components.firstOrNull()?.info?.uid
}

class IcsException(message: String) : Exception(message)

/**
 * Lectura de iCalendar con ical4j, para datos no confiables: tamaño máximo, sin acceso a la red
 * (ver ical4j.properties) y errores como [IcsException], nunca un crash.
 */
object Ics {
    /** Un evento con muchas excepciones ronda los 100 KB; 2 MB deja margen sin abrir la puerta a abusos. */
    const val MAX_CHARS = 2 * 1024 * 1024

    fun parse(text: String): List<EventInfo> = parseResource(text).components.map { it.info }

    fun parseResource(text: String): IcsResource {
        if (text.length > MAX_CHARS) throw IcsException("iCalendar demasiado grande")
        val calendar = try {
            CalendarBuilder().build(StringReader(text))
        } catch (e: Exception) {
            // ParserException, IOException o cualquier rareza interna: el archivo no se puede leer.
            throw IcsException("iCalendar ilegible")
        } catch (e: StackOverflowError) {
            throw IcsException("iCalendar ilegible")
        }
        return IcsResource(calendar.getComponents<VEvent>(Component.VEVENT).mapNotNull(::component))
    }

    /** UID del primer evento, para guardar el recurso sin interpretarlo entero. */
    fun uid(text: String): String? = try {
        parseResource(text).uid
    } catch (e: IcsException) {
        null
    }

    private fun component(event: VEvent): EventComponent? = try {
        val uid = event.prop(Property.UID)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val startProp = event.prop(Property.DTSTART) ?: return null
        val start = normalized(startProp) ?: return null
        val end = event.prop(Property.DTEND)?.let(::normalized)
        val length = when {
            start is LocalDate -> Length.Days(
                ((end as? LocalDate)?.let { ChronoUnit.DAYS.between(start, it) }
                    ?: event.duration()?.toDays())?.coerceAtLeast(1) ?: 1,
            )
            end != null && end !is LocalDate -> Length.Exact(between(start, end).coerceAtLeast(Duration.ZERO))
            else -> Length.Exact(event.duration()?.coerceAtLeast(Duration.ZERO) ?: Duration.ZERO)
        }
        @Suppress("UNCHECKED_CAST")
        val rrules = event.getProperties<RRule<Temporal>>(Property.RRULE).map { it.recur as Recur<Temporal> }
        val rdates = event.dateList(Property.RDATE)
        val exdates = event.dateList(Property.EXDATE)
        val recurrenceId = event.prop(Property.RECURRENCE_ID)?.let(::normalized)
        EventComponent(
            info = EventInfo(
                uid = uid,
                summary = event.text(Property.SUMMARY),
                location = event.text(Property.LOCATION),
                description = event.text(Property.DESCRIPTION),
                start = eventTime(start),
                end = end?.let(::eventTime),
                recurring = rrules.isNotEmpty() || rdates.isNotEmpty(),
                recurrenceId = event.prop(Property.RECURRENCE_ID)?.value,
            ),
            start = start,
            length = length,
            rrules = rrules,
            rdates = rdates,
            exdates = exdates,
            recurrenceId = recurrenceId,
        )
    } catch (e: Exception) {
        // Un VEVENT raro (fechas imposibles, RRULE inválida) se salta; el resto del archivo sigue.
        null
    }

    private fun VEvent.prop(name: String): Property? = getProperty<Property>(name).orElse(null)

    private fun VEvent.text(name: String): String? = prop(name)?.value?.takeIf { it.isNotBlank() }

    private fun VEvent.duration(): Duration? = prop(Property.DURATION)?.value?.let {
        try {
            Duration.parse(if ("W" in it) weeks(it) else it)
        } catch (e: Exception) {
            null
        }
    }

    /** "P2W" no lo entiende Duration.parse: se pasa a días. */
    private fun weeks(value: String): String {
        val sign = if (value.startsWith("-")) "-" else ""
        val n = value.trimStart('-', '+').removePrefix("P").removeSuffix("W").toLongOrNull() ?: 0
        return "${sign}P${n * 7}D"
    }

    private fun VEvent.dateList(name: String): List<Temporal> =
        getProperties<Property>(name).flatMap { p ->
            try {
                @Suppress("UNCHECKED_CAST")
                val list = (p as DateListProperty<Temporal>).dates
                val zone = systemZone(p)
                list.mapNotNull { normalize(it, zone) }
            } catch (e: Exception) {
                emptyList() // RDATE con períodos u otros valores que no se usan
            }
        }

    private fun normalized(p: Property): Temporal? {
        val temporal: Temporal = try {
            @Suppress("UNCHECKED_CAST")
            (p as DateProperty<Temporal>).date
        } catch (e: Exception) {
            return null
        } ?: return null
        return normalize(temporal, systemZone(p))
    }

    /**
     * ical4j devuelve una zona propia ("ical4j~…") que no sobrevive a un reinicio: si el TZID es
     * una zona IANA se usa la del sistema (que Android mantiene al día); la de ical4j queda solo
     * para zonas que existen únicamente en la VTIMEZONE del archivo.
     */
    private fun normalize(t: Temporal, tzid: ZoneId?): Temporal? = when (t) {
        is LocalDate -> t
        is ZonedDateTime -> if (tzid != null) t.withZoneSameLocal(tzid) else t
        is OffsetDateTime -> t.atZoneSameInstant(ZoneOffset.UTC)
        is Instant -> t.atZone(ZoneOffset.UTC)
        is LocalDateTime -> t
        else -> null
    }

    private fun systemZone(p: Property): ZoneId? {
        val tzid = p.getParameter<Parameter>(Parameter.TZID).orElse(null)?.value ?: return null
        return try {
            ZoneId.of(tzid)
        } catch (e: Exception) {
            null
        }
    }

    private fun eventTime(t: Temporal): EventTime = when (t) {
        is LocalDate -> EventTime.AllDay(t)
        is ZonedDateTime -> EventTime.At(t.toLocalDateTime(), t.zone)
        else -> EventTime.At(t as LocalDateTime, null)
    }

    private fun between(a: Temporal, b: Temporal): Duration = when {
        a is ZonedDateTime && b is ZonedDateTime -> Duration.between(a, b)
        a is LocalDateTime && b is LocalDateTime -> Duration.between(a, b)
        a is ZonedDateTime && b is LocalDateTime -> Duration.between(a.toLocalDateTime(), b)
        a is LocalDateTime && b is ZonedDateTime -> Duration.between(a, b.toLocalDateTime())
        else -> Duration.ZERO
    }
}
