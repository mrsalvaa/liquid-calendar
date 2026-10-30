package dev.liquidcalendar.core.ics

import mockwebserver3.MockWebServer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcsFilesTest {
    private fun cal(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//x//ES") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

    @Test
    fun groupsSeriesWithTheirExceptionsAndKeepsTimezones() {
        val text = cal(
            "BEGIN:VEVENT", "UID:a", "DTSTAMP:20260101T000000Z", "DTSTART;TZID=Hora propia:20261005T100000", "RRULE:FREQ=WEEKLY;COUNT=3", "SUMMARY:Serie", "END:VEVENT",
            "BEGIN:VEVENT", "UID:b", "DTSTAMP:20260101T000000Z", "DTSTART:20261030T150000Z", "SUMMARY:Suelto", "END:VEVENT",
            "BEGIN:VEVENT", "UID:a", "DTSTAMP:20260101T000000Z", "RECURRENCE-ID;TZID=Hora propia:20261012T100000", "DTSTART;TZID=Hora propia:20261012T120000", "SUMMARY:Movida", "END:VEVENT",
            // Algunos clientes ponen las VTIMEZONE al final.
            "BEGIN:VTIMEZONE", "TZID:Hora propia", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:-0300", "TZOFFSETTO:-0300", "END:STANDARD", "END:VTIMEZONE",
        )
        val p = IcsFiles.preview(text)
        assertEquals(listOf("a", "b"), p.events.map { it.uid })
        assertEquals(0, p.skipped)
        val a = p.events.first()
        assertTrue(a.series)
        assertEquals("Serie", a.summary)
        assertEquals(2, a.ics.split("BEGIN:VEVENT").size - 1)
        assertTrue("TZID:Hora propia" in a.ics)
        // La serie importada se lee con su zona propia: 10:00 a -03:00 son las 13:00 UTC.
        val first = Recurrence.expand(Ics.parseResource(a.ics), Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-06T00:00:00Z"), ZoneId.of("UTC")).single()
        assertEquals(Instant.parse("2026-10-05T13:00:00Z"), (first.time as When.Timed).start)
    }

    @Test
    fun eventsWithoutUidAreSkipped() {
        val p = IcsFiles.preview(cal("BEGIN:VEVENT", "DTSTART:20261030T150000Z", "SUMMARY:Sin UID", "END:VEVENT", "BEGIN:VEVENT", "UID:ok", "DTSTART:20261030T150000Z", "END:VEVENT"))
        assertEquals(listOf("ok"), p.events.map { it.uid })
        assertEquals(1, p.skipped)
    }

    @Test
    fun malformedFilesNeverCrash() {
        val inputs = listOf(
            "",
            "esto no es un calendario",
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\n",
            "BEGIN:VCALENDAR\r\nEND:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            "BEGIN:VCALENDAR\r\n" + "BEGIN:VALARM\r\n".repeat(5000) + "END:VCALENDAR\r\n",
            String(ByteArray(4096) { (it * 31 % 256).toByte() }, Charsets.ISO_8859_1),
            cal("BEGIN:VEVENT", "UID:sin-fecha", "SUMMARY:x", "END:VEVENT"),
        )
        for (text in inputs) {
            try {
                val p = IcsFiles.preview(text)
                assertTrue(p.events.none { it.uid == "sin-fecha" })
            } catch (e: IcsException) {
                // también vale: el archivo se rechaza con un error claro
            }
        }
    }

    @Test
    fun hugeFilesAndTooManyEventsAreRejected() {
        assertFailsWith<IcsException> { IcsFiles.preview("BEGIN:VCALENDAR\r\n" + "X".repeat(IcsFiles.MAX_IMPORT_CHARS)) }
        val many = (1..IcsFiles.MAX_IMPORT_EVENTS + 1).flatMap { listOf("BEGIN:VEVENT", "UID:$it", "DTSTART:20261030T150000Z", "END:VEVENT") }
        assertFailsWith<IcsException> { IcsFiles.preview(cal(*many.toTypedArray())) }
    }

    @Test
    fun urlsInsideTheFileAreNeverVisited() {
        val server = MockWebServer()
        server.start()
        try {
            val u = server.url("/").toString()
            val text = cal(
                "BEGIN:VTIMEZONE", "TZID:Rara", "TZURL:${u}tz", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0000", "TZOFFSETTO:+0000", "END:STANDARD", "END:VTIMEZONE",
                "BEGIN:VEVENT", "UID:u", "DTSTART;TZID=Rara:20261030T150000", "URL:${u}evento", "ATTACH:${u}adjunto.pdf",
                "ATTACH;FMTTYPE=image/png:${u}imagen.png", "IMAGE;VALUE=URI:${u}imagen2.png", "CONFERENCE;VALUE=URI:${u}sala",
                "BEGIN:VALARM", "ACTION:AUDIO", "TRIGGER:-PT5M", "ATTACH:${u}sonido.wav", "END:VALARM", "END:VEVENT",
            )
            val p = IcsFiles.preview(text)
            Recurrence.expand(Ics.parseResource(p.events.single().ics), Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), ZoneId.of("UTC"))
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun exportAndImportAgain() {
        val tz = listOf("BEGIN:VTIMEZONE", "TZID:Europe/Madrid", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0100", "END:STANDARD", "END:VTIMEZONE")
        val one = cal(*(tz + listOf("BEGIN:VEVENT", "UID:1", "DTSTART;TZID=Europe/Madrid:20261030T100000", "SUMMARY:Uno", "END:VEVENT")).toTypedArray())
        val two = cal(*(tz + listOf("BEGIN:VEVENT", "UID:2", "DTSTART;TZID=Europe/Madrid:20261031T100000", "SUMMARY:Dos", "X-RARO:conservar", "END:VEVENT")).toTypedArray())
        val exported = IcsFiles.export(listOf(one, two))
        assertEquals(1, exported.split("BEGIN:VTIMEZONE").size - 1)
        assertTrue("X-RARO:conservar" in exported)
        assertEquals(listOf("1", "2"), IcsFiles.preview(exported).events.map { it.uid })
    }

    @Test
    fun fileNamesAndDeleteConfirmation() {
        val d = LocalDate.of(2026, 10, 30)
        assertEquals("Trabajo-Facu-n-2026-10-30.ics", IcsFiles.fileName("Trabajo & Facu ñ", d))
        assertEquals("calendario-2026-10-30.ics", IcsFiles.fileName("@@@", d))
        assertTrue(IcsFiles.deleteConfirmed(" Trabajo ", "Trabajo"))
        assertFalse(IcsFiles.deleteConfirmed("trabajo", "Trabajo"))
        assertFalse(IcsFiles.deleteConfirmed("Trabaj", "Trabajo"))
        assertFalse(IcsFiles.deleteConfirmed("", ""))
    }
}
