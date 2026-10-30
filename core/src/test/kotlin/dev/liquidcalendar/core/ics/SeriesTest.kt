package dev.liquidcalendar.core.ics

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val ba = ZoneId.of("America/Argentina/Buenos_Aires")
    private val now = Instant.parse("2026-10-29T18:00:00Z")

    /** Lunes 9:00 en Madrid desde el 5/10/2026, con un EXDATE, una excepción movida y cosas de otros clientes. */
    private fun series(rule: String = "FREQ=WEEKLY;BYDAY=MO"): String = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Otro cliente//ES") + VTimezones.of(madrid, now) + listOf(
            "BEGIN:VEVENT", "UID:serie@otro", "DTSTAMP:20261001T000000Z", "SEQUENCE:4",
            "DTSTART;TZID=Europe/Madrid:20261005T090000", "DTEND;TZID=Europe/Madrid:20261005T100000",
            "RRULE:$rule", "EXDATE;TZID=Europe/Madrid:20261012T090000",
            "SUMMARY:Reunión", "LOCATION:Sala 1", "CATEGORIES:Trabajo,Equipo", "X-OTRO-CLIENTE:valor raro",
            "ORGANIZER;CN=Jefa:mailto:jefa@example.org", "ATTENDEE;CN=Ana;PARTSTAT=ACCEPTED:mailto:ana@example.org",
            "BEGIN:VALARM", "TRIGGER:-PT10M", "ACTION:DISPLAY", "DESCRIPTION:Reunión", "X-WR-ALARMUID:alarma-1", "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:serie@otro", "DTSTAMP:20261001T000000Z", "RECURRENCE-ID;TZID=Europe/Madrid:20261026T090000",
            "DTSTART;TZID=Europe/Madrid:20261026T110000", "DTEND;TZID=Europe/Madrid:20261026T120000", "SUMMARY:Reunión (movida)", "X-OTRO-CLIENTE:excepcion",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        ).joinToString("\r\n") + "\r\n"

    private val from = LocalDate.of(2026, 10, 1).atStartOfDay(madrid).toInstant()
    private val to = LocalDate.of(2026, 12, 1).atStartOfDay(madrid).toInstant()

    /** (título, inicio en Madrid) de cada ocurrencia de uno o varios recursos. */
    private fun occ(vararg ics: String) = ics.flatMap { Recurrence.expand(Ics.parseResource(it), from, to, madrid) }
        .map { it.summary to LocalDateTime.ofInstant((it.time as When.Timed).start, madrid).toString() }
        .sortedBy { it.second }

    private fun at(day: Int, month: Int = 10, h: Int = 9) = Moment.Exact(LocalDateTime.of(2026, month, day, h, 0).atZone(madrid).toInstant())

    private fun draft(start: Moment.Exact, title: String = "Reunión") = EventDraft(title, "Sala 1", null, DraftTime.Timed(start.instant, start.instant.plusSeconds(3600)))

    private fun logical(ics: String) = ics.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    private val original = occ(series())

    @Test
    fun theFixtureIsWhatWeThinkItIs() {
        // 12/10 borrado por EXDATE; 26/10 movida a las 11; el 26/10 ya es horario de invierno.
        assertEquals("2026-10-05T09:00", original.first().second)
        assertTrue(original.none { it.second.startsWith("2026-10-12") })
        assertTrue(("Reunión (movida)" to "2026-10-26T11:00") in original)
        assertEquals(8, original.size)
    }

    @Test
    fun thisOccurrenceOnly() {
        val ics = Series.edit(series(), "serie@otro", at(19), SeriesScope.THIS, draft(at(19)), draft(at(19), "Reunión con clientes"), madrid, now).original!!
        val after = occ(ics)
        assertEquals(original.map { if (it.second == "2026-10-19T09:00") "Reunión con clientes" to it.second else it }, after)
        // La excepción nueva lleva el RECURRENCE-ID con el formato del DTSTART y conserva lo de otros clientes.
        val lines = logical(ics)
        assertTrue("RECURRENCE-ID;TZID=Europe/Madrid:20261019T090000" in lines)
        assertEquals(3, lines.count { it.startsWith("X-OTRO-CLIENTE") })
        assertEquals(2, lines.count { it == "BEGIN:VALARM" })
        // El evento principal no se tocó ni un byte.
        val master = { s: String -> s.substringAfter("BEGIN:VEVENT").substringBefore("END:VEVENT") }
        assertEquals(master(series()), master(ics))
    }

    @Test
    fun editingAnExistingException() {
        val ics = Series.edit(series(), "serie@otro", at(26), SeriesScope.THIS, draft(at(26, h = 11), "Reunión (movida)"), draft(at(26, h = 12), "Reunión (movida)"), madrid, now).original!!
        assertTrue(("Reunión (movida)" to "2026-10-26T12:00") in occ(ics))
        assertEquals(2, logical(ics).count { it == "BEGIN:VEVENT" })
    }

    @Test
    fun deleteThisOccurrence() {
        val ics = Series.delete(series(), "serie@otro", at(19), SeriesScope.THIS, madrid, now).original!!
        assertEquals(original.filter { it.second != "2026-10-19T09:00" }, occ(ics))
        val lines = logical(ics)
        assertTrue("EXDATE;TZID=Europe/Madrid:20261012T090000" in lines)
        assertTrue("EXDATE;TZID=Europe/Madrid:20261019T090000" in lines)
        // Borrar la que tenía excepción la saca también.
        val ics2 = Series.delete(series(), "serie@otro", at(26), SeriesScope.THIS, madrid, now).original!!
        assertTrue(occ(ics2).none { it.second.startsWith("2026-10-26") })
        assertEquals(1, logical(ics2).count { it == "BEGIN:VEVENT" })
    }

    @Test
    fun allOccurrencesKeepExceptionsAndDaylightSaving() {
        // Todas una hora más tarde, con título nuevo.
        val ics = Series.edit(series(), "serie@otro", at(19), SeriesScope.ALL, draft(at(19)), draft(at(19, h = 10), "Reunión semanal"), madrid, now).original!!
        val after = occ(ics)
        // Las 10:00 locales se mantienen también después del cambio de horario del 25/10.
        assertTrue(after.filter { it.first == "Reunión semanal" }.all { it.second.endsWith("T10:00") })
        // El EXDATE y la excepción se corrieron con la serie: el 12/10 sigue borrado y el 26/10 sigue movido.
        assertTrue(after.none { it.second.startsWith("2026-10-12") })
        assertTrue(("Reunión (movida)" to "2026-10-26T11:00") in after)
        assertEquals(original.size, after.size)
        assertTrue("X-OTRO-CLIENTE:valor raro" in logical(ics))
    }

    @Test
    fun thisAndFollowingSplitsIntoTwoSeries() {
        val r = Series.edit(series(), "serie@otro", at(19), SeriesScope.FOLLOWING, draft(at(19)), draft(at(19), "Reunión nueva etapa"), madrid, now) { "nueva@liquidcalendar" }
        val (newUid, created) = r.created!!
        assertEquals("nueva@liquidcalendar", newUid)
        val before = occ(r.original!!)
        val afterSplit = occ(created)
        assertEquals(original.filter { it.second < "2026-10-19" }, before)
        // La serie nueva sigue desde el 19/10 con su título y se lleva la excepción del 26/10.
        assertEquals(original.filter { it.second >= "2026-10-19" }.map { if (it.first == "Reunión") "Reunión nueva etapa" to it.second else it }, afterSplit)
        assertTrue(logical(created).any { it.startsWith("UID:nueva@liquidcalendar") })
        assertTrue(logical(created).any { it.startsWith("BEGIN:VTIMEZONE") })
        assertTrue(logical(r.original!!).any { it.startsWith("RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261019T065959Z") })
        // Lo de otros clientes viaja con las dos.
        for (ics in listOf(r.original!!, created)) assertTrue(logical(ics).any { it.startsWith("ATTENDEE;CN=Ana") } && "BEGIN:VALARM" in logical(ics))
    }

    @Test
    fun followingWithCountSplitsTheCount() {
        val ics = series("FREQ=WEEKLY;BYDAY=MO;COUNT=6")
        val r = Series.edit(ics, "serie@otro", at(19), SeriesScope.FOLLOWING, draft(at(19)), draft(at(19), "B"), madrid, now) { "b@x" }
        // Antes del corte se generaron 5/10 y 12/10 (este último borrado): quedan 4 para la nueva.
        assertTrue(logical(r.created!!.second).any { it.startsWith("RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4") })
        assertEquals(occ(ics).size, occ(r.original!!, r.created!!.second).size)
    }

    @Test
    fun deleteThisAndFollowingAndFromTheFirst() {
        val ics = Series.delete(series(), "serie@otro", at(19), SeriesScope.FOLLOWING, madrid, now).original!!
        assertEquals(original.filter { it.second < "2026-10-19" }, occ(ics))
        assertNull(Series.delete(series(), "serie@otro", at(5), SeriesScope.FOLLOWING, madrid, now).original)
        assertNull(Series.delete(series(), "serie@otro", at(19), SeriesScope.ALL, madrid, now).original)
    }

    @Test
    fun affectedCounts() {
        val r = Ics.parseResource(series("FREQ=WEEKLY;BYDAY=MO;COUNT=8"))
        assertEquals(1, Series.affected(r, "serie@otro", at(19), SeriesScope.THIS, madrid))
        assertEquals(7, Series.affected(r, "serie@otro", at(19), SeriesScope.ALL, madrid)) // 8 menos el EXDATE
        assertEquals(6, Series.affected(r, "serie@otro", at(19), SeriesScope.FOLLOWING, madrid)) // 19 y 26/10, 2, 9, 16 y 23/11
        assertNull(Series.affected(Ics.parseResource(series()), "serie@otro", at(19), SeriesScope.ALL, madrid))
    }

    @Test
    fun allDaySeries() {
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//x//ES",
            "BEGIN:VEVENT", "UID:cumple", "DTSTAMP:20260101T000000Z", "DTSTART;VALUE=DATE:19900315", "DTEND;VALUE=DATE:19900316",
            "RRULE:FREQ=YEARLY", "SUMMARY:Cumple", "END:VEVENT", "END:VCALENDAR", "",
        ).joinToString("\r\n")
        val day = Moment.Date(LocalDate.of(2027, 3, 15))
        val d = EventDraft("Cumple", null, null, DraftTime.AllDay(day.date, day.date))
        val deleted = Series.delete(ics, "cumple", day, SeriesScope.THIS, ba, now).original!!
        assertTrue("EXDATE;VALUE=DATE:20270315" in logical(deleted))
        val split = Series.edit(ics, "cumple", day, SeriesScope.FOLLOWING, d, d.copy(summary = "Cumple de Ana"), ba, now) { "c2" }
        assertTrue(logical(split.original!!).any { it == "RRULE:FREQ=YEARLY;UNTIL=20270314" })
        assertTrue(logical(split.created!!.second).any { it == "DTSTART;VALUE=DATE:20270315" })
    }
}
