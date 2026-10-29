package dev.liquidcalendar.core.ics

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
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

class IcsException(message: String) : Exception(message)

/**
 * Lectura de iCalendar con ical4j, para datos no confiables: tamaño máximo, sin acceso a la red
 * (ver ical4j.properties) y errores como [IcsException], nunca un crash.
 */
object Ics {
    /** Un evento con muchas excepciones ronda los 100 KB; 2 MB deja margen sin abrir la puerta a abusos. */
    const val MAX_CHARS = 2 * 1024 * 1024

    fun parse(text: String): List<EventInfo> {
        if (text.length > MAX_CHARS) throw IcsException("iCalendar demasiado grande")
        val calendar = try {
            CalendarBuilder().build(StringReader(text))
        } catch (e: Exception) {
            // ParserException, IOException o cualquier rareza interna: el archivo no se puede leer.
            throw IcsException("iCalendar ilegible")
        } catch (e: StackOverflowError) {
            throw IcsException("iCalendar ilegible")
        }
        return calendar.getComponents<VEvent>(Component.VEVENT).mapNotNull { event ->
            val uid = event.getProperty<Property>(Property.UID).orElse(null)?.value?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val start = event.getProperty<Property>(Property.DTSTART).orElse(null)?.let(::eventTime) ?: return@mapNotNull null
            EventInfo(
                uid = uid,
                summary = event.text(Property.SUMMARY),
                location = event.text(Property.LOCATION),
                description = event.text(Property.DESCRIPTION),
                start = start,
                end = event.getProperty<Property>(Property.DTEND).orElse(null)?.let(::eventTime),
                recurring = event.getProperty<Property>(Property.RRULE).isPresent || event.getProperty<Property>(Property.RDATE).isPresent,
                recurrenceId = event.getProperty<Property>(Property.RECURRENCE_ID).orElse(null)?.value,
            )
        }
    }

    /** UID del primer evento, para guardar el recurso sin interpretarlo entero. */
    fun uid(text: String): String? = try {
        parse(text).firstOrNull()?.uid
    } catch (e: IcsException) {
        null
    }

    private fun systemZone(p: Property): ZoneId? {
        val tzid = p.getParameter<Parameter>(Parameter.TZID).orElse(null)?.value ?: return null
        return try {
            ZoneId.of(tzid)
        } catch (e: Exception) {
            null
        }
    }

    private fun VEvent.text(name: String): String? =
        getProperty<Property>(name).orElse(null)?.value?.takeIf { it.isNotBlank() }

    private fun eventTime(p: Property): EventTime? {
        val temporal: Temporal = try {
            @Suppress("UNCHECKED_CAST")
            (p as net.fortuna.ical4j.model.property.DateProperty<Temporal>).date
        } catch (e: Exception) {
            return null
        } ?: return null
        return when (temporal) {
            is LocalDate -> EventTime.AllDay(temporal)
            // ical4j devuelve una zona propia ("ical4j~…") que no sobrevive a un reinicio: si el
            // TZID es una zona IANA se usa la del sistema (que Android mantiene al día); la de
            // ical4j queda solo para zonas que existen únicamente en la VTIMEZONE del archivo.
            is ZonedDateTime -> EventTime.At(temporal.toLocalDateTime(), systemZone(p) ?: temporal.zone)
            is OffsetDateTime -> EventTime.At(temporal.toLocalDateTime(), temporal.offset)
            is Instant -> EventTime.At(LocalDateTime.ofInstant(temporal, ZoneId.of("UTC")), ZoneId.of("UTC"))
            is LocalDateTime -> EventTime.At(temporal, null)
            else -> null
        }
    }
}
