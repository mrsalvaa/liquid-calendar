package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.DraftTime
import dev.liquidcalendar.core.ics.EventDraft
import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Events
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.GlassChip
import dev.liquidcalendar.ui.glass.GlassSwitch
import dev.liquidcalendar.ui.glass.GlassTextField
import dev.liquidcalendar.ui.glass.LocalDialog
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SettingRow
import dev.liquidcalendar.ui.screens.views.DeviceClock
import dev.liquidcalendar.ui.screens.views.OriginIcon
import dev.liquidcalendar.ui.screens.views.originText
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.str
import dev.liquidcalendar.util.timeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Qué se edita: un evento nuevo (en [day]) o uno existente con su borrador. */
sealed interface EditTarget {
    data class New(val day: LocalDate) : EditTarget
    data class Existing(val calendar: CalendarInfo, val uid: String, val before: EventDraft) : EditTarget
}

@Composable
fun EventEditScreen(target: EditTarget, clock: DeviceClock, weekStart: DayOfWeek, padding: PaddingValues, onBack: () -> Unit, onSaved: () -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val zone = clock.zone
    val writable by produceState<List<CalendarInfo>?>(null) {
        value = withContext(Dispatchers.IO) { Repo.calendarInfos().values.filter { !it.readOnly }.sortedWith(compareBy({ !it.local }, { it.name })) }
    }

    val existing = target as? EditTarget.Existing
    val before = existing?.before
    // Valores iniciales: los del evento, o la próxima hora en punto del día elegido.
    val initial = remember {
        when (val t = before?.time) {
            is DraftTime.AllDay -> Initial(true, t.first, LocalTime.of(9, 0), t.lastInclusive, LocalTime.of(10, 0))
            is DraftTime.Timed -> {
                val s = t.start.atZone(zone)
                val e = t.end.atZone(zone)
                Initial(false, s.toLocalDate(), s.toLocalTime(), e.toLocalDate(), e.toLocalTime())
            }
            null -> {
                val day = (target as EditTarget.New).day
                val start = if (day == clock.today) LocalTime.now(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1) else LocalTime.of(9, 0)
                val startDay = if (day == clock.today && start == LocalTime.MIDNIGHT) day.plusDays(1) else day
                val end = start.plusHours(1)
                Initial(false, startDay, start, if (end < start) startDay.plusDays(1) else startDay, end)
            }
        }
    }
    var title by rememberSaveable { mutableStateOf(before?.summary.orEmpty()) }
    var location by rememberSaveable { mutableStateOf(before?.location.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(before?.description.orEmpty()) }
    var allDay by rememberSaveable { mutableStateOf(initial.allDay) }
    var startDate by remember { mutableStateOf(initial.startDate) }
    var startTime by remember { mutableStateOf(initial.startTime) }
    var endDate by remember { mutableStateOf(initial.endDate) }
    var endTime by remember { mutableStateOf(initial.endTime) }
    var chosen by remember { mutableStateOf(existing?.calendar) }
    var error by remember { mutableStateOf<String?>(null) }
    // Si no se tocó la fecha ni la hora, se guarda exactamente el horario original (rearmarlo
    // desde la hora local podría moverlo en la hora repetida del cambio de horario).
    var timeTouched by remember { mutableStateOf(false) }
    val calendar = chosen ?: writable?.firstOrNull()

    fun pickDate(current: LocalDate, set: (LocalDate) -> Unit) = dialog(
        DialogSpec(str(R.string.choose_date)) { dismiss ->
            DatePickerContent(current, weekStart, clock.today) {
                set(it)
                dismiss()
            }
        },
    )

    fun pickTime(current: LocalTime, set: (LocalTime) -> Unit) = dialog(
        DialogSpec(str(R.string.choose_time)) { dismiss ->
            TimePickerContent(current) {
                set(it)
                dismiss()
            }
        },
    )

    fun draft(): EventDraft? {
        if (title.isBlank()) {
            error = str(R.string.title_required)
            return null
        }
        val time = if (!timeTouched && before != null && allDay == initial.allDay) {
            before.time
        } else if (allDay) {
            if (endDate < startDate) null else DraftTime.AllDay(startDate, endDate)
        } else {
            val s = ZonedDateTime.of(startDate, startTime, zone).toInstant()
            val e = ZonedDateTime.of(endDate, endTime, zone).toInstant()
            if (e < s) null else DraftTime.Timed(s, e)
        } ?: run {
            error = str(R.string.end_before_start)
            return null
        }
        return EventDraft(title.trim(), location.trim().ifEmpty { null }, description.trim().ifEmpty { null }, time)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        BackTitle(str(if (existing == null) R.string.new_event else R.string.edit_event), onBack)

        // Siempre a la vista: en qué calendario se guarda y si es local o sincronizado.
        GlassCard(Modifier.padding(bottom = 12.dp)) {
            Text(str(R.string.saved_in), fontSize = 12.sp, color = p.contentSecondary)
            when {
                writable == null -> Unit
                calendar == null -> Text(str(R.string.no_writable_calendar), fontSize = 15.sp, color = p.danger)
                else -> SettingRow(
                    calendar.name,
                    subtitle = originText(calendar),
                    leading = {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(calendar.color)))
                        Spacer(Modifier.width(10.dp))
                        OriginIcon(calendar.local, size = 16)
                        Spacer(Modifier.width(10.dp))
                    },
                    // Al editar, el calendario no se cambia (mover eventos entre calendarios no está en esta versión).
                    onClick = if (existing == null && (writable?.size ?: 0) > 1) {
                        {
                            dialog(
                                DialogSpec(str(R.string.choose_calendar)) { dismiss ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        writable.orEmpty().forEach { c ->
                                            GlassChip("${c.name} · ${originText(c)}", c.id == calendar.id, {
                                                chosen = c
                                                dismiss()
                                            }, Modifier.fillMaxWidth())
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(title, { title = it; error = null }, str(R.string.title), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                SettingRow(str(R.string.all_day)) { GlassSwitch(allDay, { allDay = it; timeTouched = true; error = null }) }
                DateTimeRow(str(R.string.starts), startDate, if (allDay) null else startTime, { pickDate(startDate) { d -> val shift = endDate.toEpochDay() - startDate.toEpochDay(); startDate = d; endDate = d.plusDays(shift.coerceAtLeast(0)); timeTouched = true; error = null } }, { pickTime(startTime) { t -> startTime = t; timeTouched = true; error = null } })
                DateTimeRow(str(R.string.ends), endDate, if (allDay) null else endTime, { pickDate(endDate) { endDate = it; timeTouched = true; error = null } }, { pickTime(endTime) { t -> endTime = t; timeTouched = true; error = null } })
                GlassTextField(location, { location = it }, str(R.string.location), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                GlassTextField(description, { description = it }, str(R.string.description), singleLine = false, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
        }

        if (error != null) {
            Text(error!!, Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, color = p.danger)
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(str(R.string.cancel), onBack, Modifier.weight(1f))
            GlassButton(
                str(R.string.save),
                {
                    val c = calendar ?: return@GlassButton
                    val d = draft() ?: return@GlassButton
                    // Guardar sigue aunque se cierre la pantalla.
                    CalendarApp.scope.launch {
                        if (existing != null) Events.update(existing.calendar, existing.uid, existing.before, d) else Events.create(c, d)
                    }
                    onSaved()
                },
                Modifier.weight(1f),
                prominent = true,
                enabled = calendar != null,
            )
        }
    }
}

private data class Initial(val allDay: Boolean, val startDate: LocalDate, val startTime: LocalTime, val endDate: LocalDate, val endTime: LocalTime)

@Composable
private fun DateTimeRow(label: String, date: LocalDate, time: LocalTime?, onDate: () -> Unit, onTime: () -> Unit) {
    Column {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(longDate(date), false, onDate)
            if (time != null) GlassChip(timeOf(time), false, onTime)
        }
    }
}
