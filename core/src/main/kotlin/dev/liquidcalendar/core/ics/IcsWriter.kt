package dev.liquidcalendar.core.ics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Lo que el usuario puede escribir de un evento (simple, no recurrente). */
data class EventDraft(
    val summary: String,
    val location: String?,
    val description: String?,
    val time: DraftTime,
)

sealed interface DraftTime {
    /** Días completos, [first] a [lastInclusive]. Se guarda como VALUE=DATE con DTEND exclusivo. */
    data class AllDay(val first: LocalDate, val lastInclusive: LocalDate) : DraftTime {
        init {
            require(!lastInclusive.isBefore(first)) { "El último día no puede ser anterior al primero" }
        }
    }

    /** Inicio y fin exactos. */
    data class Timed(val start: Instant, val end: Instant) : DraftTime {
        init {
            require(!end.isBefore(start)) { "El fin no puede ser anterior al inicio" }
        }
    }
}

/**
 * Escritura de iCalendar. Para editar no se vuelve a generar el archivo: se cambian solo las
 * líneas de los campos tocados y el resto (X-*, ATTENDEE, ORGANIZER, VALARM, CATEGORIES y lo que
 * la app no conozca) queda byte por byte igual.
 */
object IcsWriter {
    const val PRODID = "-//Liquid Calendar//Liquid Calendar//ES"

    /** UID nuevo y único: aleatorio, sin datos del dispositivo ni del usuario. */
    fun newUid(): String = "${UUID.randomUUID()}@liquidcalendar"

    fun create(uid: String, draft: EventDraft, now: Instant = Instant.now()): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:$PRODID",
            "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT",
            "UID:${text(uid)}",
            "DTSTAMP:${utc(now)}",
            "CREATED:${utc(now)}",
            "LAST-MODIFIED:${utc(now)}",
            "SEQUENCE:0",
        )
        lines += timeLines(draft.time, keepZone = null)
        lines += "SUMMARY:${text(draft.summary)}"
        draft.location?.takeIf { it.isNotBlank() }?.let { lines += "LOCATION:${text(it)}" }
        draft.description?.takeIf { it.isNotBlank() }?.let { lines += "DESCRIPTION:${text(it)}" }
        lines += listOf("END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("") { fold(it) + CRLF }
    }

    /**
     * Aplica [draft] al VEVENT principal (sin RECURRENCE-ID) de [uid] en [original]. Solo se
     * reescriben los campos que cambiaron respecto de [before]; DTSTAMP y LAST-MODIFIED siempre,
     * y SEQUENCE sube si cambió el horario (RFC 5545).
     */
    fun patch(original: String, uid: String, before: EventDraft, draft: EventDraft, now: Instant = Instant.now()): String {
        val physical = original.replace("\r\n", "\n").replace('\r', '\n').split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        // Líneas lógicas (desplegadas), cada una con sus líneas físicas originales.
        val logical = mutableListOf<MutableList<String>>()
        for (line in physical) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && logical.isNotEmpty()) logical.last() += line else logical += mutableListOf(line)
        }
        fun unfolded(l: List<String>) = l.first() + l.drop(1).joinToString("") { it.substring(1) }
        fun nameOf(l: List<String>) = unfolded(l).substringBefore(':').substringBefore(';').uppercase()

        // Buscar el VEVENT principal de ese UID.
        var start = -1
        var end = -1
        var i = 0
        while (i < logical.size) {
            if (unfolded(logical[i]).equals("BEGIN:VEVENT", ignoreCase = true)) {
                var depth = 0
                var j = i
                var isMaster = true
                var sameUid = false
                while (j < logical.size) {
                    val u = unfolded(logical[j])
                    if (u.startsWith("BEGIN:", ignoreCase = true)) depth++
                    if (u.startsWith("END:", ignoreCase = true)) depth--
                    if (depth == 1) {
                        when (nameOf(logical[j])) {
                            "RECURRENCE-ID" -> isMaster = false
                            "UID" -> sameUid = u.substringAfter(':') == uid
                        }
                    }
                    if (depth == 0) break
                    j++
                }
                if (isMaster && sameUid) {
                    start = i
                    end = j
                    break
                }
                i = j
            }
            i++
        }
        if (start < 0 || end < 0) throw IcsException("No está el evento a editar")

        val timeChanged = before.time != draft.time
        val replace = buildSet {
            if (before.summary != draft.summary) add("SUMMARY")
            if (before.location != draft.location) add("LOCATION")
            if (before.description != draft.description) add("DESCRIPTION")
            if (timeChanged) addAll(listOf("DTSTART", "DTEND", "DURATION"))
            addAll(listOf("DTSTAMP", "LAST-MODIFIED"))
            if (timeChanged) add("SEQUENCE")
        }

        // Solo las propiedades del propio VEVENT (profundidad 1), no las de un VALARM interno.
        val body = logical.subList(start + 1, end)
        val kept = mutableListOf<MutableList<String>>()
        var depth = 0
        var insertAt = -1
        var oldSequence = 0
        var keepZone: ZoneId? = null
        for (l in body) {
            val u = unfolded(l)
            if (depth == 0) {
                val name = nameOf(l)
                if (name == "SEQUENCE") oldSequence = u.substringAfter(':').trim().toIntOrNull() ?: 0
                if (name == "DTSTART") keepZone = tzidOf(u)
                if (name in replace) {
                    if (insertAt < 0) insertAt = kept.size
                    continue
                }
            }
            if (u.startsWith("BEGIN:", ignoreCase = true)) depth++
            if (u.startsWith("END:", ignoreCase = true)) depth--
            kept += l
        }
        val fresh = buildList {
            if (timeChanged) addAll(timeLines(draft.time, keepZone))
            if ("SUMMARY" in replace) add("SUMMARY:${text(draft.summary)}")
            if ("LOCATION" in replace) draft.location?.takeIf { it.isNotBlank() }?.let { add("LOCATION:${text(it)}") }
            if ("DESCRIPTION" in replace) draft.description?.takeIf { it.isNotBlank() }?.let { add("DESCRIPTION:${text(it)}") }
            add("DTSTAMP:${utc(now)}")
            add("LAST-MODIFIED:${utc(now)}")
            if (timeChanged) add("SEQUENCE:${oldSequence + 1}")
        }.map { mutableListOf(fold(it).replace(CRLF, "\n")) }
        // Las líneas nuevas van donde estaban las viejas (o antes de los componentes internos).
        val at = if (insertAt >= 0) insertAt else kept.indexOfFirst { unfolded(it).startsWith("BEGIN:", ignoreCase = true) }.let { if (it < 0) kept.size else it }
        kept.addAll(at, fresh)

        val out = logical.subList(0, start + 1) + kept + logical.subList(end, logical.size)
        return out.flatMap { group -> group.flatMap { it.split('\n') } }.joinToString("") { it + CRLF }
    }

    /**
     * El evento principal de [uid] como borrador, tal como lo verá el formulario. [zone] solo
     * importa para las horas flotantes (sin zona), que se interpretan en la hora local.
     */
    fun draftOf(resource: IcsResource, uid: String, zone: ZoneId): EventDraft? {
        val c = resource.components.firstOrNull { it.info.uid == uid && it.recurrenceId == null } ?: return null
        val time: DraftTime = when (val s = c.start) {
            is LocalDate -> DraftTime.AllDay(s, s.plusDays(((c.length as? Length.Days)?.days ?: 1) - 1))
            else -> {
                val startInstant = when (s) {
                    is ZonedDateTime -> s.toInstant()
                    else -> (s as java.time.LocalDateTime).atZone(zone).toInstant()
                }
                val length = (c.length as? Length.Exact)?.duration ?: java.time.Duration.ZERO
                DraftTime.Timed(startInstant, startInstant.plus(length))
            }
        }
        return EventDraft(c.info.summary.orEmpty(), c.info.location, c.info.description, time)
    }

    /** true si el recurso es una serie (RRULE/RDATE) o tiene excepciones: en esta versión no se edita. */
    fun isSeries(resource: IcsResource): Boolean =
        resource.components.any { it.rrules.isNotEmpty() || it.rdates.isNotEmpty() || it.recurrenceId != null }

    // --- Fechas ---

    /**
     * Todo el día: VALUE=DATE y DTEND exclusivo. Con hora: en la zona IANA que ya tenía el evento
     * ([keepZone], cuya VTIMEZONE ya está en el archivo) o, si no, en UTC: nunca una zona inventada.
     */
    private fun timeLines(time: DraftTime, keepZone: ZoneId?): List<String> = when (time) {
        is DraftTime.AllDay -> listOf(
            "DTSTART;VALUE=DATE:${DATE.format(time.first)}",
            "DTEND;VALUE=DATE:${DATE.format(time.lastInclusive.plusDays(1))}",
        )
        is DraftTime.Timed -> if (keepZone != null) {
            listOf(
                "DTSTART;TZID=${keepZone.id}:${LOCAL.format(ZonedDateTime.ofInstant(time.start, keepZone))}",
                "DTEND;TZID=${keepZone.id}:${LOCAL.format(ZonedDateTime.ofInstant(time.end, keepZone))}",
            )
        } else {
            listOf("DTSTART:${utc(time.start)}", "DTEND:${utc(time.end)}")
        }
    }

    private fun tzidOf(line: String): ZoneId? {
        val params = line.substringBefore(':').split(';').drop(1)
        val tzid = params.firstOrNull { it.startsWith("TZID=", ignoreCase = true) }?.substringAfter('=')?.trim('"') ?: return null
        return try {
            ZoneId.of(tzid)
        } catch (e: Exception) {
            null // zona propia del archivo: el nuevo horario se escribe en UTC
        }
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun utc(i: Instant): String = UTC.format(i)

    // --- Texto ---

    /** Escapa un valor TEXT (RFC 5545 §3.3.11). */
    fun text(s: String): String = buildString {
        for (c in s.replace("\r\n", "\n")) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /** Pliega en líneas de hasta 75 octetos sin partir caracteres UTF-8. */
    fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (octets + size > limit) {
                out.append(CRLF).append(' ')
                octets = 1
                limit = 75
            }
            out.appendCodePoint(cp)
            octets += size
            i += chars
        }
        return out.toString()
    }

    private const val CRLF = "\r\n"
}
