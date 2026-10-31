package dev.liquidcalendar.core.ics

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RepeatTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val now = Instant.parse("2026-10-29T18:00:00Z")
    private val monday = LocalDate.of(2026, 10, 5)

    private fun rule(value: String, start: LocalDate = monday) = Repeats.parse(value, start, madrid)

    /** Las RRULE de los VEVENT (las VTIMEZONE también tienen, para el horario de verano). */
    private fun eventRules(ics: String): List<String> {
        var inEvent = false
        return ics.replace("\r\n ", "").split("\r\n").filter { line ->
            if (line == "BEGIN:VEVENT") inEvent = true
            if (line == "END:VEVENT") inEvent = false
            inEvent && line.startsWith("RRULE")
        }
    }

    // ---------- Leer y escribir reglas ----------

    @Test
    fun readsWhatTheEditorCanShow() {
        assertEquals(Repetition.Rule(RepeatRule(Frequency.DAILY)), rule("FREQ=DAILY"))
        assertEquals(
            Repetition.Rule(RepeatRule(Frequency.WEEKLY, 2, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), end = RepeatEnd.Count(10))),
            rule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10"),
        )
        // Semanal sin BYDAY: el día del inicio.
        assertEquals(Repetition.Rule(RepeatRule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY))), rule("FREQ=WEEKLY"))
        assertEquals(Repetition.Rule(RepeatRule(Frequency.MONTHLY, nthWeekday = 2 to DayOfWeek.TUESDAY)), rule("FREQ=MONTHLY;BYDAY=2TU"))
        assertEquals(Repetition.Rule(RepeatRule(Frequency.MONTHLY, nthWeekday = -1 to DayOfWeek.FRIDAY)), rule("FREQ=MONTHLY;BYDAY=-1FR"))
        // BYMONTHDAY del mismo día que el inicio es la regla mensual de siempre.
        assertEquals(Repetition.Rule(RepeatRule(Frequency.MONTHLY)), rule("FREQ=MONTHLY;BYMONTHDAY=5"))
        assertEquals(Repetition.Rule(RepeatRule(Frequency.YEARLY, end = RepeatEnd.Until(LocalDate.of(2030, 1, 1)))), rule("FREQ=YEARLY;UNTIL=20300101"))
        // UNTIL en UTC: el día en la zona del dispositivo (21:59:59Z = 23:59:59 en Madrid).
        assertEquals(RepeatEnd.Until(LocalDate.of(2026, 12, 31)), (rule("FREQ=DAILY;UNTIL=20261231T225959Z") as Repetition.Rule).rule.end)
    }

    @Test
    fun keepsWhatItCannotShowAsCustom() {
        listOf(
            "FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1",
            "FREQ=DAILY;BYHOUR=9,17",
            "FREQ=MONTHLY;BYMONTHDAY=15",
            "FREQ=MONTHLY;BYDAY=5MO",
            "FREQ=YEARLY;BYMONTH=3",
            "FREQ=HOURLY",
            "FREQ=DAILY;COUNT=3;UNTIL=20261231",
        ).forEach { assertIs<Repetition.Custom>(rule(it), it) }
    }

    @Test
    fun writesRfc5545() {
        val weekly = RepeatRule(Frequency.WEEKLY, 2, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), end = RepeatEnd.Count(4))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=4", Repeats.format(weekly, allDay = false, zone = madrid))
        val until = RepeatRule(Frequency.DAILY, end = RepeatEnd.Until(LocalDate.of(2026, 12, 31)))
        assertEquals("FREQ=DAILY;UNTIL=20261231T225959Z", Repeats.format(until, allDay = false, zone = madrid))
        assertEquals("FREQ=DAILY;UNTIL=20261231", Repeats.format(until, allDay = true, zone = madrid))
        assertEquals("FREQ=DAILY;UNTIL=20261231T235959", Repeats.format(until, allDay = false, zone = madrid, floating = true))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", Repeats.format(RepeatRule(Frequency.MONTHLY, nthWeekday = -1 to DayOfWeek.FRIDAY), false, madrid))
        // Ida y vuelta.
        listOf(weekly, until, RepeatRule(Frequency.MONTHLY, 3, nthWeekday = 2 to DayOfWeek.TUESDAY), RepeatRule(Frequency.YEARLY)).forEach {
            assertEquals(Repetition.Rule(it), rule(Repeats.format(it, false, madrid)))
        }
    }

    @Test
    fun followsTheNewStartDay() {
        val wednesday = monday.plusDays(2)
        val weekly = Repeats.simple(Frequency.WEEKLY, monday)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), Repeats.follow(weekly, monday, wednesday).weekdays)
        // Varios días elegidos a mano no se tocan.
        val several = weekly.copy(weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(several, Repeats.follow(several, monday, wednesday))
        // "El último viernes" pasa a "el segundo miércoles" si el día nuevo no es el último.
        val last = RepeatRule(Frequency.MONTHLY, nthWeekday = -1 to DayOfWeek.FRIDAY)
        assertEquals(2 to DayOfWeek.WEDNESDAY, Repeats.follow(last, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 14)).nthWeekday)
        assertEquals(-1 to DayOfWeek.WEDNESDAY, Repeats.follow(last, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 28)).nthWeekday)
    }

    // ---------- Eventos sueltos ----------

    private val start = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(madrid).toInstant()
    private fun single(repeat: Repetition = Repetition.None) =
        EventDraft("Clase", null, null, DraftTime.Timed(start, start.plusSeconds(3600)), repeat = repeat)

    private fun starts(ics: String, until: LocalDate = LocalDate.of(2027, 1, 1)) =
        Recurrence.expand(Ics.parseResource(ics), start.minusSeconds(1), until.atStartOfDay(madrid).toInstant(), madrid)
            .map { LocalDateTime.ofInstant((it.time as When.Timed).start, madrid).toLocalDate().toString() }

    @Test
    fun createsAnEventThatRepeats() {
        val r = Repetition.Rule(RepeatRule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), end = RepeatEnd.Count(4)))
        val ics = IcsWriter.create("clase@test", single(r), now, madrid)
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4" in ics)
        assertEquals(listOf("2026-10-05", "2026-10-07", "2026-10-12", "2026-10-14"), starts(ics))
        assertEquals(r, IcsWriter.draftOf(Ics.parseResource(ics), "clase@test", madrid)!!.repeat)
    }

    @Test
    fun addsChangesAndKeepsRulesOnASingleEvent() {
        val plain = IcsWriter.create("clase@test", single(), now, madrid)
        val daily = Repetition.Rule(RepeatRule(Frequency.DAILY, end = RepeatEnd.Until(LocalDate.of(2026, 10, 7))))
        val repeated = IcsWriter.patch(plain, "clase@test", single(), single(daily), now, madrid)
        assertEquals(listOf("2026-10-05", "2026-10-06", "2026-10-07"), starts(repeated))
        // Cambiar otra cosa no toca la regla.
        val renamed = IcsWriter.patch(repeated, "clase@test", single(daily), single(daily).copy(summary = "Taller"), now, madrid)
        assertEquals(1, eventRules(renamed).size)
        assertEquals(3, starts(renamed).size)
        // Una regla personalizada de otro cliente queda igual si no se cambia.
        val custom = plain.replace("SUMMARY:Clase", "RRULE:FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1\r\nSUMMARY:Clase")
        val draft = IcsWriter.draftOf(Ics.parseResource(custom), "clase@test", madrid)!!
        assertIs<Repetition.Custom>(draft.repeat)
        val kept = IcsWriter.patch(custom, "clase@test", draft, draft.copy(summary = "Otra"), now, madrid)
        assertTrue("RRULE:FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1" in kept)
    }

    // ---------- Series ----------

    /** La serie de SeriesTest: lunes 9:00 desde el 5/10, con un EXDATE (12/10) y una excepción (26/10). */
    private fun series(): String = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Otro cliente//ES") + VTimezones.of(madrid, now) + listOf(
            "BEGIN:VEVENT", "UID:serie@otro", "DTSTAMP:20261001T000000Z", "SEQUENCE:4",
            "DTSTART;TZID=Europe/Madrid:20261005T090000", "DTEND;TZID=Europe/Madrid:20261005T100000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE;TZID=Europe/Madrid:20261012T090000",
            "SUMMARY:Reunión", "X-OTRO-CLIENTE:valor raro",
            "BEGIN:VALARM", "TRIGGER:-PT10M", "ACTION:DISPLAY", "DESCRIPTION:Reunión", "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:serie@otro", "DTSTAMP:20261001T000000Z", "RECURRENCE-ID;TZID=Europe/Madrid:20261026T090000",
            "DTSTART;TZID=Europe/Madrid:20261026T110000", "DTEND;TZID=Europe/Madrid:20261026T120000", "SUMMARY:Reunión (movida)",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        ).joinToString("\r\n") + "\r\n"

    private fun at(day: Int) = LocalDateTime.of(2026, 10, day, 9, 0).atZone(madrid).toInstant()
    private val weeklyMonday = Repetition.Rule(RepeatRule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY)))
    private fun occurrence(day: Int, repeat: Repetition = weeklyMonday) =
        EventDraft("Reunión", null, null, DraftTime.Timed(at(day), at(day).plusSeconds(3600)), repeat = repeat)

    private fun titlesAndDays(vararg ics: String) = ics.flatMap {
        Recurrence.expand(Ics.parseResource(it), at(1), LocalDate.of(2026, 12, 1).atStartOfDay(madrid).toInstant(), madrid)
    }.map { it.summary to LocalDateTime.ofInstant((it.time as When.Timed).start, madrid).toLocalDate().toString() }.sortedBy { it.second }

    @Test
    fun theSeriesRuleIsReadForTheForm() {
        assertEquals(weeklyMonday, IcsWriter.draftOf(Ics.parseResource(series()), "serie@otro", madrid)!!.repeat)
    }

    @Test
    fun changingTheRuleForAllDropsExceptions() {
        val daily = Repetition.Rule(RepeatRule(Frequency.DAILY, end = RepeatEnd.Count(3)))
        val result = Series.edit(series(), "serie@otro", Moment.Exact(at(19)), SeriesScope.ALL, occurrence(19), occurrence(19, daily), madrid, now)
        val ics = result.original!!
        assertEquals(listOf("2026-10-05", "2026-10-06", "2026-10-07"), titlesAndDays(ics).map { it.second })
        assertFalse("EXDATE" in ics)
        assertFalse("RECURRENCE-ID" in ics)
        // Lo de otros clientes y los recordatorios siguen.
        assertTrue("X-OTRO-CLIENTE:valor raro" in ics)
        assertTrue("TRIGGER:-PT10M" in ics)
        assertEquals(listOf("RRULE:FREQ=DAILY;COUNT=3"), eventRules(ics))
    }

    @Test
    fun removingTheRuleForAllLeavesOneEvent() {
        val result = Series.edit(series(), "serie@otro", Moment.Exact(at(19)), SeriesScope.ALL, occurrence(19), occurrence(19, Repetition.None), madrid, now)
        assertEquals(listOf("Reunión" to "2026-10-05"), titlesAndDays(result.original!!))
        assertFalse(IcsWriter.isSeries(Ics.parseResource(result.original!!)))
    }

    @Test
    fun changingTheRuleFromAnOccurrenceSplitsTheSeries() {
        val mondaysAndWednesdays = Repetition.Rule(RepeatRule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), end = RepeatEnd.Count(4)))
        val result = Series.edit(series(), "serie@otro", Moment.Exact(at(19)), SeriesScope.FOLLOWING, occurrence(19), occurrence(19, mondaysAndWednesdays), madrid, now) { "nueva@test" }
        val (uid, created) = result.created!!
        assertEquals("nueva@test", uid)
        // La vieja queda hasta antes del 19 (el 12 estaba borrado); la nueva, lunes y miércoles,
        // sin la excepción del 26 (era de la regla vieja).
        assertEquals(listOf("2026-10-05"), titlesAndDays(result.original!!).map { it.second })
        assertEquals(listOf("2026-10-19", "2026-10-21", "2026-10-26", "2026-10-28"), titlesAndDays(created).map { it.second })
        assertFalse("RECURRENCE-ID" in created)
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4" in created)
    }

    @Test
    fun onlyThisNeverChangesTheRule() {
        val daily = Repetition.Rule(RepeatRule(Frequency.DAILY))
        val result = Series.edit(series(), "serie@otro", Moment.Exact(at(19)), SeriesScope.THIS, occurrence(19), occurrence(19, daily).copy(summary = "Sola"), madrid, now)
        val ics = result.original!!
        assertEquals(listOf("RRULE:FREQ=WEEKLY;BYDAY=MO"), eventRules(ics))
        assertTrue(("Sola" to "2026-10-19") in titlesAndDays(ics))
    }

    @Test
    fun countsWhatAChangeOfRuleDiscards() {
        val s = series()
        assertEquals(2, Series.discardedByRuleChange(s, "serie@otro", Moment.Exact(at(19)), SeriesScope.ALL, madrid))
        assertEquals(1, Series.discardedByRuleChange(s, "serie@otro", Moment.Exact(at(19)), SeriesScope.FOLLOWING, madrid))
        // Desde la primera, "las siguientes" son todas.
        assertEquals(2, Series.discardedByRuleChange(s, "serie@otro", Moment.Exact(at(5)), SeriesScope.FOLLOWING, madrid))
        assertEquals(0, Series.discardedByRuleChange(s, "serie@otro", Moment.Exact(at(19)), SeriesScope.THIS, madrid))
    }
}
