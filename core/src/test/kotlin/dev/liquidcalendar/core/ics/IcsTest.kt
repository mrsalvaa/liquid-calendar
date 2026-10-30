package dev.liquidcalendar.core.ics

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsTest {
    private fun cal(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//t//ES") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

    @Test
    fun readsTimedEventInItsZone() {
        val e = Ics.parse(
            cal(
                "BEGIN:VEVENT", "UID:a@b", "DTSTAMP:20261001T000000Z",
                "DTSTART;TZID=America/Argentina/Buenos_Aires:20261030T190000",
                "DTEND;TZID=America/Argentina/Buenos_Aires:20261030T203000",
                "SUMMARY:Cumple de Ana", "LOCATION:Palermo\\, CABA", "END:VEVENT",
            ),
        ).single()
        assertEquals("a@b", e.uid)
        assertEquals("Cumple de Ana", e.summary)
        assertEquals("Palermo, CABA", e.location)
        val start = e.start as EventTime.At
        assertEquals(LocalDateTime.of(2026, 10, 30, 19, 0), start.dateTime)
        assertEquals(ZoneId.of("America/Argentina/Buenos_Aires"), start.zone)
        // 19:00 en Buenos Aires (UTC-3) son las 22:00 UTC.
        assertEquals("2026-10-30T22:00:00Z", start.toInstant(ZoneId.of("Europe/Madrid")).toString())
    }

    @Test
    fun readsAllDayUtcAndFloating() {
        val events = Ics.parse(
            cal(
                "BEGIN:VEVENT", "UID:1", "DTSTAMP:20261001T000000Z", "DTSTART;VALUE=DATE:20261225", "DTEND;VALUE=DATE:20261226", "END:VEVENT",
                "BEGIN:VEVENT", "UID:2", "DTSTAMP:20261001T000000Z", "DTSTART:20261030T150000Z", "END:VEVENT",
                "BEGIN:VEVENT", "UID:3", "DTSTAMP:20261001T000000Z", "DTSTART:20261030T090000", "END:VEVENT",
            ),
        )
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 12, 25)), events[0].start)
        assertEquals("2026-10-30T15:00:00Z", (events[1].start as EventTime.At).toInstant(ZoneId.of("Asia/Tokyo")).toString())
        val floating = events[2].start as EventTime.At
        assertNull(floating.zone)
        assertEquals("2026-10-30T12:00:00Z", floating.toInstant(ZoneId.of("America/Argentina/Buenos_Aires")).toString())
    }

    @Test
    fun recognizesSeriesAndExceptions() {
        val events = Ics.parse(
            cal(
                "BEGIN:VEVENT", "UID:s", "DTSTAMP:20261001T000000Z", "DTSTART:20261005T100000Z", "RRULE:FREQ=WEEKLY;COUNT=5", "END:VEVENT",
                "BEGIN:VEVENT", "UID:s", "DTSTAMP:20261001T000000Z", "RECURRENCE-ID:20261012T100000Z", "DTSTART:20261012T110000Z", "END:VEVENT",
            ),
        )
        assertTrue(events[0].recurring)
        assertNull(events[0].recurrenceId)
        assertEquals("20261012T100000Z", events[1].recurrenceId)
    }

    @Test
    fun usesTheFileOwnVtimezone() {
        // Zona con nombre inventado: solo se puede resolver con la VTIMEZONE incluida.
        val e = Ics.parse(
            cal(
                "BEGIN:VTIMEZONE", "TZID:Mi Zona", "BEGIN:STANDARD", "DTSTART:19700101T000000",
                "TZOFFSETFROM:-0300", "TZOFFSETTO:-0300", "TZNAME:ART", "END:STANDARD", "END:VTIMEZONE",
                "BEGIN:VEVENT", "UID:z", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Mi Zona:20261030T100000", "END:VEVENT",
            ),
        ).single()
        assertEquals("z", e.uid)
        val resource = Ics.parseResource(
            cal(
                "BEGIN:VTIMEZONE", "TZID:Mi Zona", "BEGIN:STANDARD", "DTSTART:19700101T000000",
                "TZOFFSETFROM:-0300", "TZOFFSETTO:-0300", "TZNAME:ART", "END:STANDARD", "END:VTIMEZONE",
                "BEGIN:VEVENT", "UID:z", "DTSTAMP:20261001T000000Z", "DTSTART;TZID=Mi Zona:20261030T100000", "END:VEVENT",
            ),
        )
        // Las vistas convierten con la zona del archivo (no con la del dispositivo).
        val utc = ZoneId.of("UTC")
        val t = Recurrence.expand(resource, java.time.Instant.parse("2026-10-30T00:00:00Z"), java.time.Instant.parse("2026-10-31T00:00:00Z"), utc).single().time as When.Timed
        assertEquals("2026-10-30T13:00:00Z", t.start.toString())
    }

    @Test
    fun skipsEventsWithoutUidOrStart() {
        val events = Ics.parse(
            cal(
                "BEGIN:VEVENT", "DTSTAMP:20261001T000000Z", "DTSTART:20261030T150000Z", "END:VEVENT",
                "BEGIN:VEVENT", "UID:x", "DTSTAMP:20261001T000000Z", "END:VEVENT",
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun brokenInputIsAnExceptionNotACrash() {
        listOf("", "hola", "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n", "BEGIN:VCALENDAR\r\nEND:VEVENT\r\n").forEach {
            assertFailsWith<IcsException>(it) { Ics.parse(it) }
        }
        assertNull(Ics.uid("basura"))
    }

    @Test
    fun rejectsHugeInput() {
        assertFailsWith<IcsException> { Ics.parse("X".repeat(Ics.MAX_CHARS + 1)) }
    }
}
