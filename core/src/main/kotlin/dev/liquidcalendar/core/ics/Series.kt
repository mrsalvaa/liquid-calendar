package dev.liquidcalendar.core.ics

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A qué ocurrencias de una serie se aplica un cambio. */
enum class SeriesScope { THIS, FOLLOWING, ALL }

/** El inicio original de una ocurrencia (lo que sería su RECURRENCE-ID). */
sealed interface Moment {
    data class Date(val date: LocalDate) : Moment
    data class Exact(val instant: Instant) : Moment

    fun shift(by: Duration): Moment = when (this) {
        is Date -> Date(date.plusDays(by.toDays()))
        is Exact -> Exact(instant.plus(by))
    }

    /** Para ordenar y comparar con otras fechas de la serie. */
    fun key(zone: ZoneId): Instant = when (this) {
        is Date -> date.atStartOfDay(zone).toInstant()
        is Exact -> instant
    }
}

/** Resultado de un cambio sobre una serie: el recurso original modificado (o borrado) y, si se partió, uno nuevo. */
data class SeriesResult(
    /** null = la serie entera se borra. */
    val original: String?,
    /** Serie nueva ("esta y las siguientes"), con su UID. */
    val created: Pair<String, String>? = null,
)

/**
 * Edición y borrado de series sobre el texto original: solo cambian las líneas necesarias y todo
 * lo demás (X-*, ATTENDEE, ORGANIZER, VALARM...) queda igual.
 */
object Series {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    // ---------- Cantidad de ocurrencias afectadas ----------

    /**
     * Ocurrencias que toca cada alcance desde [occurrence] (null = sin fin). Cuenta lo que se ve:
     * sin las borradas con EXDATE.
     */
    fun affected(resource: IcsResource, uid: String, occurrence: Moment, scope: SeriesScope, zone: ZoneId): Int? {
        if (scope == SeriesScope.THIS) return 1
        val bounds = Recurrence.bounds(resource) ?: return 0
        val end = bounds.end ?: return null
        val all = Recurrence.expand(resource, bounds.start, end, zone).filter { it.uid == uid }
        if (scope == SeriesScope.ALL) return all.size
        val from = occurrence.key(zone)
        return all.count { startKey(it, zone) >= from }
    }

    private fun startKey(o: Occurrence, zone: ZoneId): Instant = when (val t = o.time) {
        is When.AllDay -> t.first.atStartOfDay(zone).toInstant()
        is When.Timed -> t.start
    }

    // ---------- Operaciones ----------

    fun edit(
        original: String,
        uid: String,
        occurrence: Moment,
        scope: SeriesScope,
        before: EventDraft,
        after: EventDraft,
        zone: ZoneId,
        now: Instant = Instant.now(),
        newUid: () -> String = IcsWriter::newUid,
    ): SeriesResult {
        val doc = Doc(original)
        val master = doc.master(uid) ?: throw IcsException("No está la serie")
        if (scope == SeriesScope.FOLLOWING && isFirst(doc, master, occurrence, zone)) return edit(original, uid, occurrence, SeriesScope.ALL, before, after, zone, now, newUid)
        // La repetición se cambia para todas o para las siguientes; "solo esta" nunca la toca.
        val newRule = after.repeat.takeIf { it != before.repeat && it !is Repetition.Custom && scope != SeriesScope.THIS }
        return when (scope) {
            SeriesScope.THIS -> SeriesResult(editThis(doc, master, uid, occurrence, before, after, zone, now))
            SeriesScope.ALL -> {
                editAll(doc, master, uid, before, after, zone, now)
                if (newRule != null) {
                    // Las excepciones y fechas sueltas eran de la regla vieja: se descartan.
                    doc.overrides(uid).reversed().forEach { doc.remove(it) }
                    val m = doc.master(uid)!!
                    doc.replace(m, withRule(doc.lines(m), newRule, zone))
                }
                SeriesResult(doc.render())
            }
            SeriesScope.FOLLOWING -> {
                val id = newUid()
                val delta = shiftOf(before, after)
                val created = following(doc, master, uid, occurrence, delta, id, zone, now, keepOverrides = newRule == null) { block ->
                    // La serie nueva lleva lo que se cambió (y la regla nueva, si cambió).
                    val edited = applyDraft(block, before, after, zone, now, keepRule = true)
                    if (newRule != null) withRule(edited, newRule, zone) else edited
                }
                SeriesResult(truncate(doc, master, occurrence, zone, now).render(), id to created)
            }
        }
    }

    /**
     * Cuántos cambios a ocurrencias sueltas (excepciones y ocurrencias borradas) se pierden si se
     * cambia la repetición con ese alcance desde [occurrence].
     */
    fun discardedByRuleChange(original: String, uid: String, occurrence: Moment, scope: SeriesScope, zone: ZoneId): Int {
        if (scope == SeriesScope.THIS) return 0
        val doc = Doc(original)
        val master = doc.master(uid) ?: return 0
        val all = scope == SeriesScope.ALL || isFirst(doc, master, occurrence, zone)
        val from = occurrence.key(zone)
        fun counts(m: Moment?) = m != null && (all || m.key(zone) >= from)
        return doc.overrides(uid).count { counts(doc.momentOf(it, "RECURRENCE-ID", zone)) } +
            doc.datesOf(master, "EXDATE", zone).count { counts(it) }
    }

    /**
     * El VEVENT con [repeat] como regla: fuera RRULE, RDATE, EXDATE y EXRULE; si hay regla nueva,
     * va justo después del DTSTART, con el UNTIL escrito según el tipo de inicio.
     */
    private fun withRule(block: List<String>, repeat: Repetition, zone: ZoneId): List<String> {
        var depth = 0
        val out = mutableListOf<String>()
        var startLine: String? = null
        for ((i, line) in block.withIndex()) {
            val u = Doc.unfold(line)
            if (i > 0 && u.startsWith("BEGIN:", true)) depth++
            if (depth == 0 && i > 0) {
                val name = depth0Name(u)
                if (name in setOf("RRULE", "RDATE", "EXDATE", "EXRULE")) continue
                if (name == "DTSTART") startLine = u
            }
            if (u.startsWith("END:", true) && depth > 0) depth--
            out += line
        }
        val rule = (repeat as? Repetition.Rule)?.rule ?: return out
        val start = startLine ?: return out
        val params = start.substringBefore(':').split(';').drop(1)
        val allDay = params.any { it.equals("VALUE=DATE", true) } || start.substringAfter(':').length == 8
        val floating = !allDay && params.none { it.startsWith("TZID=", true) } && !start.substringAfter(':').endsWith("Z")
        val at = out.indexOfFirst { Doc.unfold(it).let { u -> depth0Name(u) == "DTSTART" } } + 1
        out.add(at, IcsWriter.fold("RRULE:" + Repeats.format(rule, allDay, zone, floating)))
        return out
    }

    fun delete(original: String, uid: String, occurrence: Moment, scope: SeriesScope, zone: ZoneId, now: Instant = Instant.now()): SeriesResult {
        val doc = Doc(original)
        val master = doc.master(uid) ?: throw IcsException("No está la serie")
        return when {
            scope == SeriesScope.ALL -> SeriesResult(null)
            scope == SeriesScope.FOLLOWING && isFirst(doc, master, occurrence, zone) -> SeriesResult(null)
            scope == SeriesScope.FOLLOWING -> SeriesResult(truncate(doc, master, occurrence, zone, now).render())
            else -> {
                // Solo esta: EXDATE en la serie y fuera la excepción, si la había.
                doc.overrideFor(uid, occurrence, zone)?.let { doc.remove(it) }
                val m = doc.master(uid)!!
                val start = doc.propLine(m, "DTSTART")!!
                doc.insertProps(m, listOf(dateLine("EXDATE", start, occurrence, doc, zone)))
                doc.touch(doc.master(uid)!!, now)
                SeriesResult(doc.render())
            }
        }
    }

    // --- Solo esta ---

    private fun editThis(doc: Doc, master: Block, uid: String, occurrence: Moment, before: EventDraft, after: EventDraft, zone: ZoneId, now: Instant): String {
        val existing = doc.overrideFor(uid, occurrence, zone)
        if (existing != null) {
            applyDraft(doc.lines(existing), before, after, zone, now, keepRule = false).let { doc.replace(existing, it) }
            return doc.render()
        }
        // Excepción nueva: copia del evento principal, sin las reglas de repetición y con el
        // horario de esta ocurrencia (no el del comienzo de la serie).
        val start = doc.propLine(master, "DTSTART")!!
        val end = doc.propLine(master, "DTEND")
        val length = doc.lengthOf(master, zone)
        val copy = doc.lines(master)
            .filterNot { line -> depth0Name(line) in setOf("RRULE", "RDATE", "EXDATE", "EXRULE") }
            .map { line ->
                when (depth0Name(line)) {
                    "DTSTART" -> dateLine("DTSTART", start, occurrence, doc, zone)
                    "DTEND" -> dateLine("DTEND", end!!, occurrence.shift(length), doc, zone)
                    else -> line
                }
            }.toMutableList()
        val withId = insertAfterUid(copy, dateLine("RECURRENCE-ID", start, occurrence, doc, zone))
        doc.insertAfter(master, applyDraft(withId, before, after, zone, now, keepRule = false))
        return doc.render()
    }

    // --- Todas ---

    private fun editAll(doc: Doc, master: Block, uid: String, before: EventDraft, after: EventDraft, zone: ZoneId, now: Instant): String {
        val delta = shiftOf(before, after)
        // Las excepciones y fechas sueltas se corren igual que la serie, para no quedar huérfanas.
        if (!delta.isZero) {
            doc.shiftDates(master, setOf("EXDATE", "RDATE"), delta, zone)
            doc.overrides(uid).forEach { doc.shiftDates(it, setOf("RECURRENCE-ID"), delta, zone) }
        }
        val m = doc.master(uid)!!
        val masterBefore = masterDraft(doc, m, before, zone)
        val masterAfter = masterBefore.copy(
            summary = after.summary, location = after.location, description = after.description,
            time = shifted(masterBefore.time, delta, after),
        )
        doc.replace(m, applyDraft(doc.lines(m), masterBefore, masterAfter, zone, now, keepRule = true))
        return doc.render()
    }

    // --- Esta y las siguientes ---

    /** Corta la serie justo antes de [occurrence] y le saca lo que queda después. */
    private fun truncate(doc: Doc, master: Block, occurrence: Moment, zone: ZoneId, now: Instant): Doc {
        val uid = doc.uidOf(master)!!
        val from = occurrence.key(zone)
        doc.overrides(uid).filter { doc.momentOf(it, "RECURRENCE-ID", zone)?.key(zone)?.let { k -> k >= from } == true }.forEach { doc.remove(it) }
        val m = doc.master(uid)!!
        doc.filterDates(m, setOf("EXDATE", "RDATE"), zone) { it.key(zone) < from }
        val m2 = doc.master(uid)!!
        val start = doc.propLine(m2, "DTSTART")!!
        val until = when (occurrence) {
            is Moment.Date -> DATE.format(occurrence.date.minusDays(1))
            is Moment.Exact -> UTC.format(occurrence.instant.minusSeconds(1))
        }
        doc.rewriteRule(m2) { parts -> parts.filterKeys { it != "COUNT" && it != "UNTIL" } + ("UNTIL" to (if (isDate(start)) until.take(8) else until)) }
        doc.touch(doc.master(uid)!!, now)
        return doc
    }

    private fun following(
        doc: Doc,
        master: Block,
        uid: String,
        occurrence: Moment,
        delta: Duration,
        newUid: String,
        zone: ZoneId,
        now: Instant,
        keepOverrides: Boolean = true,
        edit: (MutableList<String>) -> List<String>,
    ): String {
        val original = Doc(doc.render())
        val from = occurrence.key(zone)
        val m = original.master(uid)!!
        val start = original.propLine(m, "DTSTART")!!
        // COUNT: la serie nueva se queda con las que faltaban.
        val count = original.ruleParts(m)?.get("COUNT")?.toIntOrNull()
        val remaining = count?.let { c ->
            val r = Ics.parseResource(original.render())
            val generatedBefore = Recurrence.expand(r, Recurrence.bounds(r)!!.start, from, zone).count { it.uid == uid } +
                original.datesOf(m, "EXDATE", zone).count { it.key(zone) < from }
            (c - generatedBefore).coerceAtLeast(1)
        }
        original.filterDates(m, setOf("EXDATE", "RDATE"), zone) { it.key(zone) >= from }
        if (!delta.isZero) original.shiftDates(original.master(uid)!!, setOf("EXDATE", "RDATE"), delta, zone)
        val m2 = original.master(uid)!!
        if (remaining != null) original.rewriteRule(m2) { it.filterKeys { k -> k != "COUNT" } + ("COUNT" to remaining.toString()) }
        // DTSTART de la serie nueva = la ocurrencia elegida.
        val m3 = original.master(uid)!!
        val newStart = dateLine("DTSTART", start, occurrence, original, zone)
        val masterLines = original.lines(m3).map { if (depth0Name(it) == "DTSTART") newStart else it }.toMutableList()
        val endLine = original.propLine(m3, "DTEND")
        val lines = if (endLine != null) {
            val length = original.lengthOf(m3, zone)
            masterLines.map { if (depth0Name(it) == "DTEND") dateLine("DTEND", endLine, occurrence.shift(length), original, zone) else it }.toMutableList()
        } else {
            masterLines
        }
        val newMaster = edit(renameUid(lines, newUid).toMutableList())
        val overrides = original.overrides(uid)
            .filter { keepOverrides && original.momentOf(it, "RECURRENCE-ID", zone)?.key(zone)?.let { k -> k >= from } == true }
            .map { o ->
                val d = Doc.of(original.lines(o))
                if (!delta.isZero) d.shiftDates(d.blocks().first(), setOf("RECURRENCE-ID"), delta, zone, original.fileZones)
                renameUid(d.lines(d.blocks().first()), newUid)
            }
        val header = original.header()
        val body = listOf(newMaster) + overrides
        val created = Doc.of(header + body.flatten() + "END:VCALENDAR")
        created.touch(created.master(newUid)!!, now, resetSequence = true)
        return created.render()
    }

    // ---------- Apoyo ----------

    private fun isFirst(doc: Doc, master: Block, occurrence: Moment, zone: ZoneId): Boolean =
        doc.momentOf(master, "DTSTART", zone)?.key(zone)?.let { occurrence.key(zone) <= it } ?: false

    private fun shiftOf(before: EventDraft, after: EventDraft): Duration = when {
        before.time is DraftTime.AllDay && after.time is DraftTime.AllDay ->
            Duration.ofDays(ChronoUnit.DAYS.between((before.time as DraftTime.AllDay).first, (after.time as DraftTime.AllDay).first))
        before.time is DraftTime.Timed && after.time is DraftTime.Timed ->
            Duration.between((before.time as DraftTime.Timed).start, (after.time as DraftTime.Timed).start)
        else -> throw IcsException("Una serie no puede pasar de todo el día a con hora (ni al revés)")
    }

    /** El horario del principal, movido como la ocurrencia, con la duración nueva. */
    private fun shifted(master: DraftTime, delta: Duration, after: EventDraft): DraftTime = when (master) {
        is DraftTime.AllDay -> {
            val a = after.time as DraftTime.AllDay
            val first = master.first.plusDays(delta.toDays())
            DraftTime.AllDay(first, first.plusDays(ChronoUnit.DAYS.between(a.first, a.lastInclusive)))
        }
        is DraftTime.Timed -> {
            val a = after.time as DraftTime.Timed
            val start = master.start.plus(delta)
            DraftTime.Timed(start, start.plus(Duration.between(a.start, a.end)))
        }
    }

    /** El borrador del evento principal (para "todas", que parte de su horario, no del de la ocurrencia). */
    private fun masterDraft(doc: Doc, master: Block, occurrenceDraft: EventDraft, zone: ZoneId): EventDraft {
        val r = Ics.parseResource(doc.render())
        val uid = doc.uidOf(master)!!
        return IcsWriter.draftOf(r, uid, zone)!!.copy(summary = occurrenceDraft.summary, location = occurrenceDraft.location, description = occurrenceDraft.description)
    }

    /** Aplica [after] a un bloque VEVENT (solo los campos que cambiaron respecto de [before]). */
    private fun applyDraft(block: List<String>, before: EventDraft, after: EventDraft, zone: ZoneId, now: Instant, keepRule: Boolean): List<String> {
        val uid = block.first { depth0Name(it) == "UID" }.substringAfter(':')
        val wrapped = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:${IcsWriter.PRODID}") + block + "END:VCALENDAR").joinToString("\r\n") + "\r\n"
        // Para la excepción nueva, "antes" es lo que se copió del principal con el horario de la ocurrencia.
        // La regla nunca se cambia acá (ver withRule): solo los textos, el horario y los recordatorios.
        val patched = IcsWriter.patch(wrapped, uid, before, after.copy(repeat = before.repeat), now, zone, ignoreRecurrenceId = true)
        return Doc(patched).let { d -> d.lines(d.blocks().first()) }
    }

    private fun insertAfterUid(lines: MutableList<String>, line: String): MutableList<String> {
        val i = lines.indexOfFirst { depth0Name(it) == "UID" }
        lines.add(i + 1, line)
        return lines
    }

    private fun renameUid(lines: List<String>, uid: String): List<String> = lines.map { if (depth0Name(it) == "UID") "UID:${IcsWriter.text(uid)}" else it }

    internal fun depth0Name(line: String): String = line.substringBefore(':').substringBefore(';').uppercase()

    private fun isDate(line: String) = line.substringBefore(':').split(';').any { it.equals("VALUE=DATE", ignoreCase = true) }

    /** Una fecha escrita con el mismo formato (VALUE, TZID) que la propiedad de referencia [like]. */
    internal fun dateLine(name: String, like: String, m: Moment, doc: Doc, zone: ZoneId): String {
        val params = like.substringBefore(':').split(';').drop(1).filter { it.startsWith("TZID=", true) || it.startsWith("VALUE=", true) }
        val head = (listOf(name) + params).joinToString(";")
        return "$head:${format(like, m, doc.fileZones, zone)}"
    }

    internal fun format(like: String, m: Moment, fileZones: Map<String, FileZone>, zone: ZoneId): String {
        val params = like.substringBefore(':').split(';').drop(1)
        val tzid = params.firstOrNull { it.startsWith("TZID=", true) }?.substringAfter('=')?.trim('"')
        return when (m) {
            is Moment.Date -> DATE.format(m.date)
            is Moment.Exact -> when {
                tzid != null -> try {
                    LOCAL.format(ZonedDateTime.ofInstant(m.instant, ZoneId.of(tzid)))
                } catch (e: Exception) {
                    LOCAL.format(fileZones[tzid]?.toLocal(m.instant) ?: LocalDateTime.ofInstant(m.instant, zone))
                }
                like.substringAfter(':').endsWith("Z") -> UTC.format(m.instant)
                else -> LOCAL.format(LocalDateTime.ofInstant(m.instant, zone))
            }
        }
    }

    internal fun parse(line: String, value: String, fileZones: Map<String, FileZone>, zone: ZoneId): Moment? = try {
        val params = line.substringBefore(':').split(';').drop(1)
        val tzid = params.firstOrNull { it.startsWith("TZID=", true) }?.substringAfter('=')?.trim('"')
        when {
            value.length == 8 -> Moment.Date(LocalDate.parse(value, DATE))
            value.endsWith("Z") -> Moment.Exact(LocalDateTime.parse(value.removeSuffix("Z"), LOCAL).toInstant(ZoneOffset.UTC))
            tzid != null -> {
                val local = LocalDateTime.parse(value, LOCAL)
                Moment.Exact(
                    try {
                        local.atZone(ZoneId.of(tzid)).toInstant()
                    } catch (e: Exception) {
                        fileZones[tzid]?.toInstant(local) ?: local.atZone(zone).toInstant()
                    },
                )
            }
            else -> Moment.Exact(LocalDateTime.parse(value, LOCAL).atZone(zone).toInstant())
        }
    } catch (e: Exception) {
        null
    }
}
