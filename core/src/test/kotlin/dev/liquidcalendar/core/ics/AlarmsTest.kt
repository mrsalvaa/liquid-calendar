package dev.liquidcalendar.core.ics

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmsTest {
    private val ba = ZoneId.of("America/Argentina/Buenos_Aires")
    private val now = Instant.parse("2026-10-29T18:00:00Z")

    private fun fixture(name: String) = javaClass.getResource("/ics/$name")!!.readText()

    private fun logical(ics: String) = ics.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    /** Bloques VALARM tal cual (líneas lógicas). */
    private fun alarmBlocks(ics: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var cur: MutableList<String>? = null
        for (l in logical(ics)) {
            if (l == "BEGIN:VALARM") cur = mutableListOf()
            cur?.add(l)
            if (l == "END:VALARM") { out += cur!!; cur = null }
        }
        return out
    }

    @Test
    fun parsesTriggers() {
        assertEquals(Duration.ofMinutes(-15), Alarms.duration("-PT15M"))
        assertEquals(Duration.ofDays(-1), Alarms.duration("-P1D"))
        assertEquals(Duration.ofDays(-14), Alarms.duration("-P2W"))
        assertEquals(Duration.ofDays(-1).minusHours(2).minusMinutes(30), Alarms.duration("-P1DT2H30M"))
        assertEquals(Duration.ZERO, Alarms.duration("PT0S"))
        assertNull(Alarms.duration("mañana"))
        for (d in listOf(Duration.ofMinutes(-15), Duration.ofDays(-1), Duration.ZERO, Duration.ofDays(-1).minusHours(2), Duration.ofHours(1))) {
            assertEquals(d, Alarms.duration(Alarms.format(d)))
        }
        val related = Alarms.parse(listOf("BEGIN:VALARM", "TRIGGER;RELATED=END:-PT5M", "ACTION:DISPLAY", "END:VALARM"))!!
        assertEquals(Trigger.Relative(Duration.ofMinutes(-5), fromEnd = true), related.trigger)
        val absolute = Alarms.parse(listOf("BEGIN:VALARM", "TRIGGER;VALUE=DATE-TIME:20261030T120000Z", "ACTION:DISPLAY", "END:VALARM"))!!
        assertEquals(Trigger.Absolute(Instant.parse("2026-10-30T12:00:00Z")), absolute.trigger)
    }

    @Test
    fun whenTheyRing() {
        val timed = When.Timed(Instant.parse("2026-10-30T22:00:00Z"), Instant.parse("2026-10-30T23:00:00Z"))
        assertEquals(Instant.parse("2026-10-30T21:45:00Z"), Alarms.triggerAt(AlarmSpec(Trigger.Relative(Duration.ofMinutes(-15))), timed, ba))
        assertEquals(Instant.parse("2026-10-30T22:55:00Z"), Alarms.triggerAt(AlarmSpec(Trigger.Relative(Duration.ofMinutes(-5), fromEnd = true)), timed, ba))
        // Todo el día: desde la medianoche local (00:00 en Buenos Aires = 03:00 UTC); un día antes a las 9.
        val allDay = When.AllDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        assertEquals(Instant.parse("2026-11-01T12:00:00Z"), Alarms.triggerAt(AlarmSpec(Trigger.Relative(Duration.ofHours(-15))), allDay, ba))
    }

    @Test
    fun readsAlarmsOfEachComponentAndOccurrence() {
        val r = Ics.parseResource(fixture("davx5.ics"))
        val alarms = r.components.single().alarms
        assertEquals(listOf(Trigger.Relative(Duration.ofHours(-1)), Trigger.Relative(Duration.ofDays(-1))), alarms.map { it.trigger })
        val from = LocalDate.of(2026, 11, 5).atStartOfDay(ba).toInstant()
        assertEquals(alarms, Recurrence.expand(r, from, from.plusSeconds(86400), ba).single().alarms)
    }

    @Test
    fun removingOneAlarmKeepsTheOtherByteForByte() {
        val original = fixture("davx5.ics")
        val r = Ics.parseResource(original)
        val before = IcsWriter.draftOf(r, r.uid!!, ba)!!
        val edited = IcsWriter.patch(original, r.uid!!, before, before.copy(alarms = before.alarms.drop(1)), now, ba)
        assertEquals(alarmBlocks(original).drop(1), alarmBlocks(edited))
    }

    @Test
    fun addingAnAlarmLeavesTheExistingOnes() {
        val original = fixture("davx5.ics")
        val r = Ics.parseResource(original)
        val before = IcsWriter.draftOf(r, r.uid!!, ba)!!
        val edited = IcsWriter.patch(original, r.uid!!, before, before.copy(alarms = before.alarms + AlarmSpec(Trigger.Relative(Duration.ofMinutes(-10)))), now, ba)
        val blocks = alarmBlocks(edited)
        assertEquals(alarmBlocks(original), blocks.take(2))
        assertEquals(listOf("BEGIN:VALARM", "TRIGGER:-PT10M", "ACTION:DISPLAY", "DESCRIPTION:Turno dentista", "END:VALARM"), blocks[2])
        // Volver a leerlo da los tres.
        assertEquals(3, Ics.parseResource(edited).components.single().alarms.size)
    }

    @Test
    fun editingSomethingElseDoesNotTouchAlarms() {
        val original = fixture("odysseus.ics")
        val r = Ics.parseResource(original)
        val before = IcsWriter.draftOf(r, r.uid!!, ba)!!
        val edited = IcsWriter.patch(original, r.uid!!, before, before.copy(summary = "Otra cosa"), now, ba)
        assertEquals(alarmBlocks(original), alarmBlocks(edited))
    }

    @Test
    fun newEventsWithAlarms() {
        val start = Instant.parse("2026-11-04T12:00:00Z")
        val ics = IcsWriter.create("u", EventDraft("Parcial", null, null, DraftTime.Timed(start, start.plusSeconds(3600)), listOf(AlarmSpec(Trigger.Relative(Duration.ofDays(-1))), AlarmSpec(Trigger.Relative(Duration.ofMinutes(-30))))), now, ba)
        val alarms = Ics.parseResource(ics).components.single().alarms.map { it.trigger }
        assertEquals(listOf(Trigger.Relative(Duration.ofDays(-1)), Trigger.Relative(Duration.ofMinutes(-30))), alarms)
        assertTrue("TRIGGER:-P1D" in logical(ics))
    }
}
