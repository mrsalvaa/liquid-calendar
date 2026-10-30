package dev.liquidcalendar.core.ics

import java.text.Normalizer
import java.time.LocalDate

/** Un evento (con sus excepciones) listo para guardar como un recurso propio. */
data class ImportedEvent(val uid: String, val ics: String, val summary: String?, val series: Boolean)

/** Lo que se puede importar de un archivo y lo que se salteó (sin UID o ilegible). */
data class ImportPreview(val events: List<ImportedEvent>, val skipped: Int)

/**
 * Importar y exportar archivos .ics. Datos no confiables: límites de tamaño y de cantidad, y
 * ninguna URL del archivo (ATTACH, URL, TZURL...) se visita nunca: solo se lee texto.
 */
object IcsFiles {
    const val MAX_IMPORT_CHARS = 5 * 1024 * 1024
    const val MAX_IMPORT_EVENTS = 5000

    /**
     * Divide el archivo en un recurso por UID (la serie junto con sus excepciones), cada uno con
     * las VTIMEZONE del archivo. Lanza [IcsException] si es demasiado grande, tiene demasiados
     * eventos o no es un iCalendar.
     */
    fun preview(text: String): ImportPreview {
        if (text.length > MAX_IMPORT_CHARS) throw IcsException("Archivo demasiado grande")
        if (!text.trimStart().startsWith("BEGIN:VCALENDAR", ignoreCase = true)) throw IcsException("No es un archivo iCalendar")
        val doc = Doc(text)
        val blocks = doc.blocks()
        if (blocks.size > MAX_IMPORT_EVENTS) throw IcsException("Demasiados eventos")
        val timezones = timezoneBlocks(doc)
        var skipped = 0
        val byUid = LinkedHashMap<String, MutableList<List<String>>>()
        for (b in blocks) {
            val uid = doc.uidOf(b)?.trim()
            if (uid.isNullOrEmpty()) {
                skipped++
                continue
            }
            byUid.getOrPut(uid) { mutableListOf() } += doc.lines(b)
        }
        val events = byUid.mapNotNull { (uid, list) ->
            val ics = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:${IcsWriter.PRODID}") + timezones.flatten() + list.flatten() + "END:VCALENDAR")
                .joinToString("") { it + "\r\n" }
            // Cada recurso tiene que poder leerse; si no, se saltea (sin romper el resto).
            val resource = try {
                Ics.parseResource(ics)
            } catch (e: IcsException) {
                null
            }
            if (resource == null || resource.components.isEmpty()) {
                skipped += list.size
                null
            } else {
                ImportedEvent(uid, ics, resource.components.firstOrNull { it.recurrenceId == null }?.info?.summary, IcsWriter.isSeries(resource))
            }
        }
        return ImportPreview(events, skipped)
    }

    /** Un solo archivo con varios eventos: un encabezado, cada VTIMEZONE una vez y todos los VEVENT. */
    fun export(resources: List<String>): String {
        val zones = LinkedHashMap<String, List<String>>()
        val events = mutableListOf<String>()
        for (text in resources) {
            val doc = Doc(text)
            for (z in timezoneBlocks(doc)) {
                val id = z.firstOrNull { Doc.unfold(it).startsWith("TZID:", true) }?.let { Doc.unfold(it).substringAfter(':') } ?: continue
                zones.putIfAbsent(id, z)
            }
            doc.blocks().forEach { events += doc.lines(it) }
        }
        val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:${IcsWriter.PRODID}", "CALSCALE:GREGORIAN") +
            zones.values.flatten() + events + "END:VCALENDAR"
        return lines.joinToString("") { it + "\r\n" }
    }

    /** Nombre de archivo para exportar: el nombre del calendario y la fecha, sin datos del servidor. */
    fun fileName(name: String, date: LocalDate): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val clean = ascii.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "-").take(40).ifEmpty { "calendario" }
        return "$clean-$date.ics"
    }

    /** Borrar un calendario exige escribir su nombre tal cual (sin contar espacios en los bordes). */
    fun deleteConfirmed(typed: String, name: String): Boolean = name.isNotBlank() && typed.trim() == name.trim()

    private fun timezoneBlocks(doc: Doc): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        for (l in doc.header() + doc.tail()) {
            val u = Doc.unfold(l)
            if (u.equals("BEGIN:VTIMEZONE", true)) current = mutableListOf()
            current?.add(l)
            if (u.equals("END:VTIMEZONE", true)) {
                current?.let { out += it }
                current = null
            }
        }
        return out
    }
}
