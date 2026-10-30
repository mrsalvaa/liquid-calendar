package dev.liquidcalendar.core.ics

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IcsWriterTest {
    private val now = Instant.parse("2026-10-29T18:00:00Z")
    private val ba = ZoneId.of("America/Argentina/Buenos_Aires")
    private val zones = listOf(ba, ZoneId.of("Europe/Madrid"), ZoneId.of("America/New_York"), ZoneId.of("Asia/Kolkata"), ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Pacific/Pago_Pago"), ZoneId.of("UTC"))

    private fun fixture(name: String) = javaClass.getResource("/ics/$name")!!.readText()

    /** Líneas lógicas (desplegadas) de un .ics. */
    private fun logical(ics: String): List<String> {
        val out = mutableListOf<String>()
        for (l in ics.split("\r\n").filter { it.isNotEmpty() }) {
            if (l.startsWith(" ") || l.startsWith("\t")) out[out.lastIndex] = out.last() + l.substring(1) else out += l
        }
        return out
    }

    private fun only(ics: String): EventComponent = Ics.parseResource(ics).components.single()

    @Test
    fun createdAllDayIsValueDateWithExclusiveEnd() {
        val ics = IcsWriter.create("u1", EventDraft("Feriado", null, null, DraftTime.AllDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20))), now)
        assertTrue("DTSTART;VALUE=DATE:20261120" in logical(ics))
        assertTrue("DTEND;VALUE=DATE:20261121" in logical(ics))
        // Visto desde cualquier zona, sigue siendo el 20/11 y solo ese día.
        for (zone in zones) {
            val from = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant()
            val to = LocalDate.of(2026, 12, 1).atStartOfDay(zone).toInstant()
            val occ = Recurrence.expand(Ics.parseResource(ics), from, to, zone).single()
            assertEquals(When.AllDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21)), occ.time, zone.id)
        }
    }

    @Test
    fun timedEventsKeepTheirExactInstantInEveryZone() {
        // Incluye horas de los cambios de horario de verano de Madrid y Nueva York.
        val starts = listOf(
            LocalDateTime.of(2026, 10, 30, 19, 0).atZone(ba).toInstant(),
            LocalDateTime.of(2026, 3, 29, 1, 30).atZone(ZoneId.of("Europe/Madrid")).toInstant(),
            LocalDateTime.of(2026, 10, 25, 2, 30).atZone(ZoneId.of("Europe/Madrid")).toInstant(),
            LocalDateTime.of(2026, 11, 1, 1, 45).atZone(ZoneId.of("America/New_York")).toInstant(),
            LocalDateTime.of(2026, 12, 31, 23, 30).atZone(ZoneId.of("Pacific/Kiritimati")).toInstant(),
        )
        for (start in starts) {
            val end = start.plusSeconds(5400)
            val ics = IcsWriter.create("u", EventDraft("x", null, null, DraftTime.Timed(start, end)), now)
            for (zone in zones) {
                val occ = Recurrence.expand(Ics.parseResource(ics), start.minusSeconds(86400), end.plusSeconds(86400), zone).single()
                assertEquals(When.Timed(start, end), occ.time, "$start ${zone.id}")
                // Y el borrador que se vuelve a leer es el mismo: guardar no mueve el evento.
                assertEquals(DraftTime.Timed(start, end), IcsWriter.draftOf(Ics.parseResource(ics), "u", zone)!!.time)
            }
        }
    }

    @Test
    fun textSurvivesEscapingAndFolding() {
        val summary = "Cumple de Ana; traer torta, globos \\ y 🎂 — ñandú"
        val description = "Línea 1\nLínea 2, con coma; y punto y coma\n" + "x".repeat(200)
        val ics = IcsWriter.create("u", EventDraft(summary, "Palermo, CABA", description, DraftTime.AllDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))), now)
        ics.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75, "línea de más de 75 octetos: $it") }
        val info = only(ics).info
        assertEquals(summary, info.summary)
        assertEquals("Palermo, CABA", info.location)
        assertEquals(description, info.description)
    }

    @Test
    fun editingOnlyTheTitleKeepsEverythingElseByteForByte() {
        for (name in listOf("davx5.ics", "odysseus.ics")) {
            val original = fixture(name)
            val resource = Ics.parseResource(original)
            val uid = resource.uid!!
            val before = IcsWriter.draftOf(resource, uid, ba)!!
            val edited = IcsWriter.patch(original, uid, before, before.copy(summary = "Título nuevo"), now)
            val changed = setOf("SUMMARY", "DTSTAMP", "LAST-MODIFIED")
            fun key(l: String) = l.substringBefore(':').substringBefore(';').uppercase()
            // Todas las líneas que no son de los campos cambiados del VEVENT siguen idénticas y en orden.
            val o = logical(original).filterNot { key(it) in changed && !it.startsWith("DESCRIPTION:Mañana") }
            val e = logical(edited).filterNot { key(it) in changed && !it.startsWith("DESCRIPTION:Mañana") }
            assertEquals(o, e, name)
            assertEquals("Título nuevo", Ics.parseResource(edited).components.single().info.summary)
            // Lo que la app no conoce sigue ahí.
            val must = if (name == "davx5.ics") {
                listOf("BEGIN:VALARM", "ATTENDEE;CN=Ana", "ORGANIZER", "CATEGORIES:Salud,Personal", "X-MOZ-GENERATION", "X-ANDROID-COLOR", "CLASS:PRIVATE")
            } else {
                listOf("BEGIN:VALARM", "X-APPLE-TRAVEL-ADVISORY", "X-APPLE-STRUCTURED-LOCATION", "ATTACH;FMTTYPE", "URL;VALUE=URI", "X-WR-ALARMUID")
            }
            for (m in must) assertTrue(logical(edited).any { it.startsWith(m) }, "$name perdió $m")
        }
    }

    @Test
    fun alarmsInsideTheEventAreNotTouched() {
        // El VALARM de DAVx5 tiene su propio DESCRIPTION: cambiar la descripción del evento no lo toca.
        val original = fixture("davx5.ics")
        val r = Ics.parseResource(original)
        val before = IcsWriter.draftOf(r, r.uid!!, ba)!!
        val edited = IcsWriter.patch(original, r.uid!!, before, before.copy(description = "Otra cosa"), now)
        val alarms = { s: String -> logical(s).dropWhile { it != "BEGIN:VALARM" }.takeWhile { it != "END:VEVENT" } }
        assertEquals(alarms(original), alarms(edited))
        assertEquals("Otra cosa", Ics.parseResource(edited).components.single().info.description)
    }

    @Test
    fun editingTheTimeKeepsTheOriginalZoneAndBumpsSequence() {
        val original = fixture("odysseus.ics")
        val r = Ics.parseResource(original)
        val uid = r.uid!!
        val before = IcsWriter.draftOf(r, uid, ba)!!
        // Una hora más tarde, cruzando ya el horario de invierno (25/10/2026 en Madrid).
        val t = before.time as DraftTime.Timed
        val after = before.copy(time = DraftTime.Timed(t.start.plusSeconds(3600), t.end.plusSeconds(3600)))
        val edited = IcsWriter.patch(original, uid, before, after, now)
        assertTrue("DTSTART;TZID=Europe/Madrid:20261025T110000" in logical(edited))
        assertTrue("DTEND;TZID=Europe/Madrid:20261025T120000" in logical(edited))
        assertTrue("SEQUENCE:3" in logical(edited))
        assertTrue(logical(original).filter { it.startsWith("BEGIN:VTIMEZONE") || it.startsWith("TZOFFSET") } == logical(edited).filter { it.startsWith("BEGIN:VTIMEZONE") || it.startsWith("TZOFFSET") })
        // Al volver a leerlo, el horario es exactamente el que se guardó.
        assertEquals(after.time, IcsWriter.draftOf(Ics.parseResource(edited), uid, ba)!!.time)
    }

    @Test
    fun aZoneThatOnlyExistsInTheFileIsRewrittenInUtc() {
        val original = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//x//x//ES",
            "BEGIN:VTIMEZONE", "TZID:Hora de Mi Casa", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:-0300", "TZOFFSETTO:-0300", "END:STANDARD", "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:z", "DTSTAMP:20260101T000000Z", "DTSTART;TZID=Hora de Mi Casa:20261030T100000", "DTEND;TZID=Hora de Mi Casa:20261030T110000", "SUMMARY:x", "END:VEVENT",
            "END:VCALENDAR", "",
        ).joinToString("\r\n")
        val before = IcsWriter.draftOf(Ics.parseResource(original), "z", ba)!!
        val start = Instant.parse("2026-10-30T15:00:00Z")
        val edited = IcsWriter.patch(original, "z", before, before.copy(time = DraftTime.Timed(start, start.plusSeconds(3600))), now)
        // Se conserva la zona del archivo; 15:00 UTC con su desfase de -03:00 son las 12:00.
        assertTrue("DTSTART;TZID=Hora de Mi Casa:20261030T120000" in logical(edited))
        assertEquals(start, IcsWriter.draftOf(Ics.parseResource(edited), "z", ba)!!.time.let { (it as DraftTime.Timed).start })
    }

    @Test
    fun newTimedEventsCarryTzidAndVtimezoneAndRoundTrip() {
        for (zone in listOf(ZoneId.of("Europe/Madrid"), ZoneId.of("America/New_York"), ba)) {
            // Incluye horas del día del cambio de horario de cada zona.
            val locals = listOf(LocalDateTime.of(2026, 3, 29, 3, 30), LocalDateTime.of(2026, 10, 25, 1, 15), LocalDateTime.of(2026, 3, 8, 3, 0), LocalDateTime.of(2026, 11, 1, 0, 45), LocalDateTime.of(2026, 7, 1, 12, 0))
            for (local in locals) {
                val start = local.atZone(zone).toInstant()
                val draft = EventDraft("x", null, null, DraftTime.Timed(start, start.plusSeconds(3600)))
                val ics = IcsWriter.create("u", draft, now, zone)
                assertTrue("DTSTART;TZID=${zone.id}:${local.atZone(zone).toLocalDateTime().toString().replace("-", "").replace(":", "").padEnd(15, '0')}" in logical(ics), "$zone $local")
                assertEquals(1, logical(ics).count { it == "TZID:${zone.id}" })
                // Leído desde otra zona, es el mismo instante.
                for (device in zones) assertEquals(draft.time, IcsWriter.draftOf(Ics.parseResource(ics), "u", device)!!.time, "$zone $local ${device.id}")
            }
        }
    }

    @Test
    fun floatingStaysFloatingAndUtcGainsTheDeviceZone() {
        fun event(start: String) = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//x//x//ES", "BEGIN:VEVENT", "UID:f", "DTSTAMP:20260101T000000Z", start, "DURATION:PT1H", "SUMMARY:x", "END:VEVENT", "END:VCALENDAR", "").joinToString("\r\n")
        val floating = event("DTSTART:20261030T090000")
        val before = IcsWriter.draftOf(Ics.parseResource(floating), "f", ba)!!
        val later = (before.time as DraftTime.Timed).let { DraftTime.Timed(it.start.plusSeconds(3600), it.end.plusSeconds(3600)) }
        val edited = IcsWriter.patch(floating, "f", before, before.copy(time = later), now, ba)
        assertTrue("DTSTART:20261030T100000" in logical(edited))
        assertTrue(logical(edited).none { it.startsWith("BEGIN:VTIMEZONE") })

        val utc = event("DTSTART:20261030T120000Z")
        val b2 = IcsWriter.draftOf(Ics.parseResource(utc), "f", ba)!!
        val t2 = (b2.time as DraftTime.Timed).let { DraftTime.Timed(it.start.plusSeconds(1800), it.end.plusSeconds(1800)) }
        val e2 = IcsWriter.patch(utc, "f", b2, b2.copy(time = t2), now, ba)
        assertTrue("DTSTART;TZID=America/Argentina/Buenos_Aires:20261030T093000" in logical(e2))
        assertEquals(1, logical(e2).count { it == "BEGIN:VTIMEZONE" })
        assertEquals(t2, IcsWriter.draftOf(Ics.parseResource(e2), "f", ZoneId.of("Asia/Tokyo"))!!.time)
    }

    @Test
    fun allDayToTimedAndBack() {
        val ics = IcsWriter.create("u", EventDraft("x", null, null, DraftTime.AllDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4))), now)
        val r = Ics.parseResource(ics)
        val before = IcsWriter.draftOf(r, "u", ba)!!
        assertEquals(DraftTime.AllDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4)), before.time)
        val timed = before.copy(time = DraftTime.Timed(Instant.parse("2026-11-02T13:00:00Z"), Instant.parse("2026-11-02T14:00:00Z")))
        val edited = IcsWriter.patch(ics, "u", before, timed, now)
        assertEquals(timed.time, IcsWriter.draftOf(Ics.parseResource(edited), "u", ba)!!.time)
        // Sin DURATION ni DTEND duplicados.
        assertEquals(1, logical(edited).count { it.startsWith("DTEND") })
    }

    @Test
    fun newUidsAreUniqueAndRevealNothing() {
        val uids = (1..2000).map { IcsWriter.newUid() }.toSet()
        assertEquals(2000, uids.size)
        assertTrue(uids.all { it.endsWith("@liquidcalendar") && it.length == 36 + "@liquidcalendar".length })
        assertNotEquals(IcsWriter.newUid(), IcsWriter.newUid())
    }

    @Test
    fun invalidDraftsAreRejected() {
        assertFailsWith<IllegalArgumentException> { DraftTime.AllDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)) }
        assertFailsWith<IllegalArgumentException> { DraftTime.Timed(Instant.parse("2026-11-02T13:00:00Z"), Instant.parse("2026-11-02T12:00:00Z")) }
        assertFailsWith<IcsException> {
            IcsWriter.patch(fixture("davx5.ics"), "otro-uid", EventDraft("", null, null, DraftTime.AllDay(LocalDate.now(), LocalDate.now())), EventDraft("x", null, null, DraftTime.AllDay(LocalDate.now(), LocalDate.now())), now)
        }
    }
}
