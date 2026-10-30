package dev.liquidcalendar.core.ics

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZonesTest {
    private val zones = listOf("Europe/Madrid", "America/New_York", "America/Argentina/Buenos_Aires", "Asia/Kolkata", "Australia/Lord_Howe").map(ZoneId::of)
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    /** Un .ics con la VTIMEZONE generada para [zone], pero con un TZID inventado (zona "solo del archivo"). */
    private fun withFileZone(zone: ZoneId, vararg event: String): String {
        val vtz = VTimezones.of(zone, now).map { if (it.startsWith("TZID:")) "TZID:Zona del archivo" else it }
        return (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//t//ES") + vtz + event.toList() + "END:VCALENDAR").joinToString("\r\n") + "\r\n"
    }

    @Test
    fun generatedVtimezoneMatchesTheSystemRulesEveryHour() {
        for (zone in zones) {
            val ics = withFileZone(zone, "BEGIN:VEVENT", "UID:x", "DTSTAMP:20260101T000000Z", "DTSTART;TZID=Zona del archivo:20260101T000000", "END:VEVENT")
            val fz = Ics.parseResource(ics).fileZones.getValue("Zona del archivo")
            var t = LocalDateTime.of(2025, 1, 1, 0, 30)
            var checked = 0
            while (t.year < 2031) {
                val valid = zone.rules.getValidOffsets(t)
                // Las horas que no existen o se repiten en el cambio de horario no tienen una única respuesta.
                if (valid.size == 1) {
                    assertEquals(valid.single(), fz.offsetAt(t), "${zone.id} $t")
                    checked++
                }
                t = t.plusHours(1)
            }
            assertTrue(checked > 50_000, "${zone.id}: $checked")
        }
    }

    @Test
    fun noDaylightSavingZoneHasASingleStandard() {
        val lines = VTimezones.of(ZoneId.of("America/Argentina/Buenos_Aires"), now)
        assertEquals(1, lines.count { it == "BEGIN:STANDARD" })
        assertEquals(0, lines.count { it == "BEGIN:DAYLIGHT" })
        assertTrue("TZOFFSETTO:-0300" in lines)
    }

    @Test
    fun seriesInAFileOnlyZoneFollowItsDaylightSaving() {
        val madrid = ZoneId.of("Europe/Madrid")
        // Lunes 9:00 en "la zona del archivo" (con las reglas de Madrid), cruzando el 25/10/2026.
        val ics = withFileZone(madrid, "BEGIN:VEVENT", "UID:s", "DTSTAMP:20260101T000000Z", "DTSTART;TZID=Zona del archivo:20261019T090000", "DURATION:PT1H", "RRULE:FREQ=WEEKLY;COUNT=3", "END:VEVENT")
        for (device in listOf(ZoneId.of("America/Argentina/Buenos_Aires"), ZoneId.of("Asia/Tokyo"))) {
            val from = LocalDate.of(2026, 10, 1).atStartOfDay(device).toInstant()
            val to = LocalDate.of(2026, 11, 30).atStartOfDay(device).toInstant()
            val got = Recurrence.expand(Ics.parseResource(ics), from, to, device).map { (it.time as When.Timed).start }
            val expected = listOf(19, 26).map { LocalDateTime.of(2026, 10, it, 9, 0).atZone(madrid).toInstant() } + LocalDateTime.of(2026, 11, 2, 9, 0).atZone(madrid).toInstant()
            assertEquals(expected, got, device.id)
        }
    }

    @Test
    fun unknownTzidWithoutVtimezoneIsFloating() {
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//t//ES",
            "BEGIN:VEVENT", "UID:f", "DTSTAMP:20260101T000000Z", "DTSTART;TZID=Zona Inexistente:20261030T100000", "DURATION:PT1H", "END:VEVENT",
            "END:VCALENDAR", "",
        ).joinToString("\r\n")
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val from = LocalDate.of(2026, 10, 30).atStartOfDay(zone).toInstant()
        val t = Recurrence.expand(Ics.parseResource(ics), from, from.plusSeconds(86400), zone).single().time as When.Timed
        assertEquals(LocalDateTime.of(2026, 10, 30, 10, 0), LocalDateTime.ofInstant(t.start, zone))
    }
}
