package dev.liquidcalendar.core.ics

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Un VEVENT de primer nivel: índices de su BEGIN y su END en [Doc]. */
internal data class Block(val start: Int, val end: Int)

/**
 * Un .ics como lista de líneas lógicas. Cada línea conserva su plegado original (CRLF + espacio),
 * así que lo que no se toca sale exactamente igual.
 */
internal class Doc private constructor(private val items: MutableList<String>) {
    constructor(text: String) : this(logical(text))

    val fileZones: Map<String, FileZone> by lazy {
        try {
            Ics.parseResource(render()).fileZones
        } catch (e: IcsException) {
            emptyMap()
        }
    }

    fun render(): String = items.joinToString("") { it + CRLF }

    // --- Bloques ---

    fun blocks(): List<Block> {
        val out = mutableListOf<Block>()
        var depth = 0
        var open = -1
        items.forEachIndexed { i, raw ->
            val l = unfold(raw)
            if (l.startsWith("BEGIN:", true)) {
                depth++
                if (depth == 2 && l.equals("BEGIN:VEVENT", true)) open = i
            } else if (l.startsWith("END:", true)) {
                if (depth == 2 && l.equals("END:VEVENT", true) && open >= 0) {
                    out += Block(open, i)
                    open = -1
                }
                depth--
            }
        }
        // Un VEVENT suelto (sin VCALENDAR alrededor), como los bloques copiados.
        if (out.isEmpty() && items.firstOrNull()?.let { unfold(it).equals("BEGIN:VEVENT", true) } == true) {
            val end = items.indexOfLast { unfold(it).equals("END:VEVENT", true) }
            if (end > 0) out += Block(0, end)
        }
        return out
    }

    fun lines(b: Block): List<String> = items.subList(b.start, b.end + 1).toList()

    /** Propiedades del propio VEVENT (no las de un VALARM interno), con su índice. */
    private fun props(b: Block): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var depth = 0
        for (i in b.start + 1 until b.end) {
            val l = unfold(items[i])
            if (l.startsWith("BEGIN:", true)) depth++
            if (depth == 0) out += i to l
            if (l.startsWith("END:", true)) depth--
        }
        return out
    }

    fun propLine(b: Block, name: String): String? = props(b).firstOrNull { Series.depth0Name(it.second) == name }?.second

    fun uidOf(b: Block): String? = propLine(b, "UID")?.substringAfter(':')

    fun master(uid: String): Block? = blocks().firstOrNull { uidOf(it) == uid && propLine(it, "RECURRENCE-ID") == null }

    fun overrides(uid: String): List<Block> = blocks().filter { uidOf(it) == uid && propLine(it, "RECURRENCE-ID") != null }

    fun overrideFor(uid: String, m: Moment, zone: ZoneId): Block? =
        overrides(uid).firstOrNull { momentOf(it, "RECURRENCE-ID", zone)?.key(zone) == m.key(zone) }

    // --- Cambios ---

    fun remove(b: Block) {
        repeat(b.end - b.start + 1) { items.removeAt(b.start) }
    }

    fun replace(b: Block, lines: List<String>) {
        remove(b)
        items.addAll(b.start, lines)
    }

    fun insertAfter(b: Block, lines: List<String>) {
        items.addAll(b.end + 1, lines)
    }

    /** Agrega propiedades al VEVENT, antes de sus componentes internos (VALARM...). */
    fun insertProps(b: Block, lines: List<String>) {
        val firstNested = (b.start + 1 until b.end).firstOrNull { unfold(items[it]).startsWith("BEGIN:", true) } ?: b.end
        items.addAll(firstNested, lines.map { IcsWriter.fold(it) })
    }

    private fun replaceProp(b: Block, name: String, value: String?) {
        val existing = props(b).filter { Series.depth0Name(it.second) == name }.map { it.first }
        existing.sortedDescending().forEach { items.removeAt(it) }
        if (value != null) {
            val updated = blocks().first { it.start == b.start }
            insertProps(updated, listOf(value))
        }
    }

    /** DTSTAMP y LAST-MODIFIED al momento; SEQUENCE +1 (o 0 en una serie nueva). */
    fun touch(b: Block, now: Instant, resetSequence: Boolean = false) {
        val stamp = UTC.format(now)
        val seq = if (resetSequence) 0 else (propLine(b, "SEQUENCE")?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0) + 1
        replaceProp(b, "DTSTAMP", "DTSTAMP:$stamp")
        replaceProp(blocks().first { it.start == b.start }, "LAST-MODIFIED", "LAST-MODIFIED:$stamp")
        replaceProp(blocks().first { it.start == b.start }, "SEQUENCE", "SEQUENCE:$seq")
    }

    // --- Fechas ---

    fun datesOf(b: Block, name: String, zone: ZoneId): List<Moment> =
        props(b).filter { Series.depth0Name(it.second) == name }.flatMap { (_, l) ->
            l.substringAfter(':').split(',').mapNotNull { Series.parse(l, it.trim(), fileZones, zone) }
        }

    fun momentOf(b: Block, name: String, zone: ZoneId): Moment? = datesOf(b, name, zone).firstOrNull()

    /** Corre las fechas de esas propiedades (EXDATE, RDATE, RECURRENCE-ID) conservando su formato. */
    fun shiftDates(b: Block, names: Set<String>, by: Duration, zone: ZoneId, zones: Map<String, FileZone> = fileZones) {
        rewriteDates(b, names) { line, value -> Series.parse(line, value, zones, zone)?.shift(by)?.let { Series.format(line, it, zones, zone) } ?: value }
    }

    /** Deja solo las fechas que cumplen [keep]; una propiedad que queda vacía se borra. */
    fun filterDates(b: Block, names: Set<String>, zone: ZoneId, keep: (Moment) -> Boolean) {
        rewriteDates(b, names) { line, value -> Series.parse(line, value, fileZones, zone)?.let { if (keep(it)) value else null } ?: value }
    }

    private fun rewriteDates(b: Block, names: Set<String>, map: (String, String) -> String?) {
        for ((i, l) in props(b).reversed()) {
            if (Series.depth0Name(l) !in names) continue
            val values = l.substringAfter(':').split(',').mapNotNull { map(l, it.trim()) }
            if (values.isEmpty()) items.removeAt(i) else items[i] = IcsWriter.fold(l.substringBefore(':') + ":" + values.joinToString(","))
        }
    }

    fun lengthOf(b: Block, zone: ZoneId): Duration {
        val s = momentOf(b, "DTSTART", zone) ?: return Duration.ZERO
        val e = momentOf(b, "DTEND", zone) ?: return Duration.ZERO
        return when {
            s is Moment.Date && e is Moment.Date -> Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(s.date, e.date))
            s is Moment.Exact && e is Moment.Exact -> Duration.between(s.instant, e.instant)
            else -> Duration.ZERO
        }
    }

    // --- RRULE ---

    fun ruleParts(b: Block): LinkedHashMap<String, String>? = propLine(b, "RRULE")?.substringAfter(':')?.split(';')?.filter { '=' in it }
        ?.associateTo(LinkedHashMap()) { it.substringBefore('=').uppercase() to it.substringAfter('=') }

    fun rewriteRule(b: Block, transform: (Map<String, String>) -> Map<String, String>) {
        val (i, line) = props(b).firstOrNull { Series.depth0Name(it.second) == "RRULE" } ?: return
        val parts = transform(ruleParts(b)!!)
        // FREQ primero, el resto en el orden original.
        val ordered = listOfNotNull(parts["FREQ"]?.let { "FREQ=$it" }) + parts.filterKeys { it != "FREQ" }.map { "${it.key}=${it.value}" }
        items[i] = IcsWriter.fold(line.substringBefore(':') + ":" + ordered.joinToString(";"))
    }

    /** Todo lo que va antes del primer VEVENT (encabezado y VTIMEZONE). */
    fun header(): List<String> = items.takeWhile { !unfold(it).equals("BEGIN:VEVENT", true) }

    companion object {
        private const val CRLF = "\r\n"
        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun of(lines: List<String>): Doc = Doc(lines.toMutableList())

        fun unfold(raw: String): String = raw.replace("\r\n ", "").replace("\r\n\t", "")

        private fun logical(text: String): MutableList<String> {
            val out = mutableListOf<String>()
            for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                if (line.isEmpty()) continue
                if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + CRLF + line else out += line
            }
            return out
        }
    }
}
