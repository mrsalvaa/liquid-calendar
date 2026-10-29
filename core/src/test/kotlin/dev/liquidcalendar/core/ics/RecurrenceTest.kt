package dev.liquidcalendar.core.ics

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceTest {
    private val ba = ZoneId.of("America/Argentina/Buenos_Aires")
    private val madrid = ZoneId.of("Europe/Madrid")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14

    private fun res(vararg lines: String) = Ics.parseResource(
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//t//ES") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n",
    )

    private fun ev(vararg lines: String) = arrayOf("BEGIN:VEVENT", "DTSTAMP:20260101T000000Z", *lines, "END:VEVENT")

    private fun range(from: String, to: String, zone: ZoneId) =
        LocalDate.parse(from).atStartOfDay(zone).toInstant() to LocalDate.parse(to).atStartOfDay(zone).toInstant()

    private fun startsIn(occ: List<Occurrence>, zone: ZoneId) =
        occ.map { (it.time as When.Timed).start.atZone(zone).toLocalDateTime().toString() }

    @Test
    fun weeklySeriesOnlyInsideTheRange() {
        val r = res(*ev("UID:w", "DTSTART;TZID=America/Argentina/Buenos_Aires:20261005T100000", "DURATION:PT1H", "RRULE:FREQ=WEEKLY;BYDAY=MO"))
        val (from, to) = range("2026-11-01", "2026-12-01", ba)
        assertEquals(
            listOf("2026-11-02T10:00", "2026-11-09T10:00", "2026-11-16T10:00", "2026-11-23T10:00", "2026-11-30T10:00"),
            startsIn(Recurrence.expand(r, from, to, ba), ba),
        )
    }

    @Test
    fun keepsLocalTimeAcrossDaylightSaving() {
        // Madrid cambia de hora el 25/10/2026: la reunión sigue a las 9:00 locales.
        val r = res(*ev("UID:d", "DTSTART;TZID=Europe/Madrid:20261019T090000", "DTEND;TZID=Europe/Madrid:20261019T100000", "RRULE:FREQ=WEEKLY;COUNT=3"))
        val (from, to) = range("2026-10-01", "2026-11-30", madrid)
        val occ = Recurrence.expand(r, from, to, madrid)
        assertEquals(listOf("2026-10-19T09:00", "2026-10-26T09:00", "2026-11-02T09:00"), startsIn(occ, madrid))
        // Visto desde Buenos Aires, la hora cambia por el horario de verano de Madrid.
        assertEquals(listOf("2026-10-19T04:00", "2026-10-26T05:00", "2026-11-02T05:00"), startsIn(occ, ba))
        // La duración se respeta en cada ocurrencia.
        occ.forEach { val t = it.time as When.Timed; assertEquals(3600, t.end.epochSecond - t.start.epochSecond) }
    }

    @Test
    fun exceptionsReplaceTheirOccurrence() {
        val r = res(
            *ev("UID:s", "DTSTART;TZID=America/Argentina/Buenos_Aires:20261005T100000", "DTEND;TZID=America/Argentina/Buenos_Aires:20261005T110000", "RRULE:FREQ=WEEKLY;BYDAY=MO", "SUMMARY:Reunión"),
            *ev("UID:s", "RECURRENCE-ID;TZID=America/Argentina/Buenos_Aires:20261012T100000", "DTSTART;TZID=America/Argentina/Buenos_Aires:20261012T120000", "DTEND;TZID=America/Argentina/Buenos_Aires:20261012T130000", "SUMMARY:Reunión (movida)"),
        )
        val (from, to) = range("2026-10-05", "2026-10-20", ba)
        val occ = Recurrence.expand(r, from, to, ba)
        assertEquals(listOf("2026-10-05T10:00", "2026-10-12T12:00", "2026-10-19T10:00"), startsIn(occ, ba))
        assertEquals("Reunión (movida)", occ[1].summary)
        // La clave de la ocurrencia movida es su inicio original.
        assertTrue(occ[1].key.endsWith(ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, ba).toInstant().toString()))
    }

    @Test
    fun exceptionMovedOutsideTheRangeLeavesNoGhost() {
        val r = res(
            *ev("UID:s", "DTSTART:20261005T130000Z", "DURATION:PT1H", "RRULE:FREQ=WEEKLY;COUNT=4"),
            *ev("UID:s", "RECURRENCE-ID:20261012T130000Z", "DTSTART:20261201T130000Z", "DURATION:PT1H"),
        )
        val (from, to) = range("2026-10-10", "2026-10-15", ba)
        assertTrue(Recurrence.expand(r, from, to, ba).isEmpty())
        // Y aparece donde se movió.
        val (f2, t2) = range("2026-12-01", "2026-12-02", ba)
        assertEquals(1, Recurrence.expand(r, f2, t2, ba).size)
    }

    @Test
    fun exdateWrittenInAnotherZoneStillMatches() {
        // 12/10 10:00 en Buenos Aires = 13:00 UTC.
        val r = res(*ev("UID:x", "DTSTART;TZID=America/Argentina/Buenos_Aires:20261005T100000", "DURATION:PT1H", "RRULE:FREQ=WEEKLY;COUNT=3", "EXDATE:20261012T130000Z"))
        val (from, to) = range("2026-10-01", "2026-11-01", ba)
        assertEquals(listOf("2026-10-05T10:00", "2026-10-19T10:00"), startsIn(Recurrence.expand(r, from, to, ba), ba))
    }

    @Test
    fun untilAndLastFridayOfTheMonth() {
        val r = res(*ev("UID:m", "DTSTART;TZID=America/Argentina/Buenos_Aires:20260130T180000", "DURATION:PT2H", "RRULE:FREQ=MONTHLY;BYDAY=-1FR;UNTIL=20260501T000000Z"))
        val (from, to) = range("2026-01-01", "2027-01-01", ba)
        assertEquals(
            listOf("2026-01-30T18:00", "2026-02-27T18:00", "2026-03-27T18:00", "2026-04-24T18:00"),
            startsIn(Recurrence.expand(r, from, to, ba), ba),
        )
    }

    @Test
    fun allDayEventsNeverShiftDay() {
        val r = res(*ev("UID:a", "DTSTART;VALUE=DATE:20261102", "DTEND;VALUE=DATE:20261103", "SUMMARY:Entrega"))
        for (zone in listOf(ba, ZoneId.of("UTC"), kiritimati, ZoneId.of("Pacific/Pago_Pago"))) {
            val (from, to) = range("2026-11-01", "2026-11-08", zone)
            val occ = Recurrence.expand(r, from, to, zone).single()
            assertEquals(When.AllDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)), occ.time, zone.id)
            // Y no aparece el día anterior ni el siguiente.
            val (f1, t1) = range("2026-11-01", "2026-11-02", zone)
            val (f3, t3) = range("2026-11-03", "2026-11-04", zone)
            assertTrue(Recurrence.expand(r, f1, t1, zone).isEmpty(), zone.id)
            assertTrue(Recurrence.expand(r, f3, t3, zone).isEmpty(), zone.id)
        }
    }

    @Test
    fun multiDayAllDayAndYearlyBirthdays() {
        val trip = res(*ev("UID:v", "DTSTART;VALUE=DATE:20261228", "DTEND;VALUE=DATE:20270104"))
        val (from, to) = range("2027-01-01", "2027-01-02", ba)
        assertEquals(When.AllDay(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 4)), Recurrence.expand(trip, from, to, ba).single().time)
        val bday = res(*ev("UID:b", "DTSTART;VALUE=DATE:19900315", "RRULE:FREQ=YEARLY"))
        val (f2, t2) = range("2027-03-01", "2027-04-01", ba)
        assertEquals(When.AllDay(LocalDate.of(2027, 3, 15), LocalDate.of(2027, 3, 16)), Recurrence.expand(bday, f2, t2, ba).single().time)
    }

    @Test
    fun floatingTimeUsesTheDeviceZone() {
        val r = res(*ev("UID:f", "DTSTART:20261030T090000", "DTEND:20261030T093000"))
        for (zone in listOf(ba, madrid)) {
            val (from, to) = range("2026-10-30", "2026-10-31", zone)
            val t = Recurrence.expand(r, from, to, zone).single().time as When.Timed
            assertEquals(LocalTime.of(9, 0), t.start.atZone(zone).toLocalTime(), zone.id)
        }
    }

    @Test
    fun eventStartedBeforeTheRangeIsStillShown() {
        val r = res(*ev("UID:n", "DTSTART;TZID=America/Argentina/Buenos_Aires:20261031T230000", "DTEND;TZID=America/Argentina/Buenos_Aires:20261101T020000", "RRULE:FREQ=WEEKLY;COUNT=2"))
        val (from, to) = range("2026-11-01", "2026-11-02", ba)
        assertEquals(listOf("2026-10-31T23:00"), startsIn(Recurrence.expand(r, from, to, ba), ba))
    }

    @Test
    fun oldDailySeriesFarInTheFutureIsFastAndCapped() {
        val r = res(*ev("UID:o", "DTSTART:20000101T120000Z", "DURATION:PT15M", "RRULE:FREQ=DAILY"))
        val (from, to) = range("2030-06-01", "2030-06-08", ba)
        val t0 = System.nanoTime()
        assertEquals(7, Recurrence.expand(r, from, to, ba).size)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "demasiado lento")
        // Una regla por minuto durante un año no devuelve más del tope.
        val absurd = res(*ev("UID:z", "DTSTART:20260101T000000Z", "RRULE:FREQ=MINUTELY"))
        val (f2, t2) = range("2026-01-01", "2027-01-01", ba)
        assertTrue(Recurrence.expand(absurd, f2, t2, ba).size <= Recurrence.MAX_PER_SERIES)
    }

    @Test
    fun fastForwardGivesTheSameOccurrences() {
        // Adelantar el inicio de la serie es solo una optimización: el resultado tiene que ser
        // idéntico al de recorrerla desde el principio, para reglas y rangos muy distintos.
        val rules = listOf(
            "FREQ=DAILY", "FREQ=DAILY;INTERVAL=3", "FREQ=WEEKLY", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR",
            "FREQ=WEEKLY;BYDAY=SU;WKST=MO", "FREQ=MONTHLY", "FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=15",
            "FREQ=MONTHLY;BYDAY=-1FR", "FREQ=MONTHLY;BYDAY=2TU", "FREQ=MONTHLY;BYMONTHDAY=31",
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "FREQ=YEARLY", "FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
            "FREQ=DAILY;UNTIL=20290101T000000Z", "FREQ=WEEKLY;COUNT=500",
        )
        val starts = listOf(
            "DTSTART;TZID=America/Argentina/Buenos_Aires:20150107T093000",
            "DTSTART;TZID=Europe/Madrid:20180331T230000",
            "DTSTART:20120229T120000Z",
            "DTSTART;VALUE=DATE:20130131",
            "DTSTART:20160105T080000",
        )
        val windows = listOf("2026-10-25" to "2026-12-06", "2028-02-27" to "2028-03-05", "2031-03-29" to "2031-04-01")
        for (rule in rules) for (start in starts) for ((f, t) in windows) for (zone in listOf(ba, madrid)) {
            val r = res(*ev("UID:p", start, "DURATION:PT45M", "RRULE:$rule"))
            val (from, to) = range(f, t, zone)
            assertEquals(
                Recurrence.expand(r, from, to, zone, fastForward = false),
                Recurrence.expand(r, from, to, zone, fastForward = true),
                "$rule | $start | $f | ${zone.id}",
            )
        }
    }

    @Test
    fun boundsForTheDatabaseFilter() {
        val single = Recurrence.bounds(res(*ev("UID:1", "DTSTART:20261030T150000Z", "DURATION:PT1H")))!!
        assertTrue(single.start < Instant.parse("2026-10-30T15:00:00Z") && single.end!! > Instant.parse("2026-10-30T16:00:00Z"))
        assertNull(Recurrence.bounds(res(*ev("UID:2", "DTSTART:20261030T150000Z", "RRULE:FREQ=WEEKLY")))!!.end)
        val counted = Recurrence.bounds(res(*ev("UID:3", "DTSTART:20261005T150000Z", "DURATION:PT1H", "RRULE:FREQ=WEEKLY;COUNT=3")))!!
        assertTrue(counted.end!! > Instant.parse("2026-10-19T16:00:00Z") && counted.end!! < Instant.parse("2026-10-21T00:00:00Z"))
        val until = Recurrence.bounds(res(*ev("UID:4", "DTSTART;VALUE=DATE:20261001", "RRULE:FREQ=DAILY;UNTIL=20261010")))!!
        assertNotNull(until.end)
    }
}
