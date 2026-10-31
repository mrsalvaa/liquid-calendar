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
import dev.liquidcalendar.notify.rememberNotificationPermission
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.GlassChip
import dev.liquidcalendar.ui.glass.GlassSwitch
import dev.liquidcalendar.ui.glass.GlassTextField
import dev.liquidcalendar.ui.glass.LocalDialog
import dev.liquidcalendar.ui.glass.LocalActionSheet
import dev.liquidcalendar.core.ics.Moment
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import dev.liquidcalendar.ui.glass.GlassSurface
import dev.liquidcalendar.ui.glass.styled
import dev.liquidcalendar.core.ics.RepeatEnd
import dev.liquidcalendar.core.ics.Repeats
import dev.liquidcalendar.core.ics.Repetition

/** Qué se edita: un evento nuevo (en [day]) o uno existente con su borrador. */
sealed interface EditTarget {
    data class New(val day: LocalDate) : EditTarget
    /** [occurrence] no nulo = es una ocurrencia de una serie (al guardar se elige el alcance). */
    data class Existing(val calendar: CalendarInfo, val uid: String, val before: EventDraft, val occurrence: Moment? = null) : EditTarget
}

@Composable
fun EventEditScreen(target: EditTarget, clock: DeviceClock, weekStart: DayOfWeek, padding: PaddingValues, onBack: () -> Unit, onSaved: () -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val sheet = LocalActionSheet.current
    val uiScope = rememberCoroutineScope()
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
    var alarms by remember { mutableStateOf(before?.alarms.orEmpty()) }
    var repeat by remember { mutableStateOf(before?.repeat ?: Repetition.None) }
    // Con recordatorios, se pide el permiso de notificaciones al guardar (si todavía no se dio).
    val requestNotifications = rememberNotificationPermission()
    val isSeries = existing?.occurrence != null
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
        val until = ((repeat as? Repetition.Rule)?.rule?.end as? RepeatEnd.Until)?.date
        if (until != null && until < startDate) {
            error = str(R.string.repeat_until_before)
            return null
        }
        return EventDraft(title.trim(), location.trim().ifEmpty { null }, description.trim().ifEmpty { null }, time, alarms, repeat)
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
                        OriginIcon(calendar, size = 16)
                        Spacer(Modifier.width(10.dp))
                    },
                    // Al editar, el calendario no se cambia (mover eventos entre calendarios no está en esta versión).
                    onClick = if (existing == null && (writable?.size ?: 0) > 1) {
                        {
                            dialog(
                                DialogSpec(str(R.string.choose_calendar)) { dismiss ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        writable.orEmpty().forEach { c ->
                                            CalendarChoice(c, c.id == calendar.id) {
                                                chosen = c
                                                dismiss()
                                            }
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
                if (isSeries) {
                    // Una serie no cambia entre todo el día y con horario (cambiaría el tipo de cada ocurrencia).
                    SettingRow(str(R.string.all_day), subtitle = str(R.string.series_all_day_locked))
                } else {
                    SettingRow(str(R.string.all_day)) { GlassSwitch(allDay, { allDay = it; timeTouched = true; error = null }) }
                }
                DateTimeRow(str(R.string.starts), startDate, if (allDay) null else startTime, { pickDate(startDate) { d -> val shift = endDate.toEpochDay() - startDate.toEpochDay(); (repeat as? Repetition.Rule)?.let { repeat = Repetition.Rule(Repeats.follow(it.rule, startDate, d)) }; startDate = d; endDate = d.plusDays(shift.coerceAtLeast(0)); timeTouched = true; error = null } }, { pickTime(startTime) { t -> startTime = t; timeTouched = true; error = null } })
                DateTimeRow(str(R.string.ends), endDate, if (allDay) null else endTime, { pickDate(endDate) { endDate = it; timeTouched = true; error = null } }, { pickTime(endTime) { t -> endTime = t; timeTouched = true; error = null } })
                RepeatEditor(
                    repeat,
                    startDate,
                    weekStart,
                    onChange = { repeat = it; error = null },
                    onPickUntil = { current ->
                        pickDate(current) { d ->
                            (repeat as? Repetition.Rule)?.let { repeat = Repetition.Rule(it.rule.copy(end = RepeatEnd.Until(d))) }
                            error = null
                        }
                    },
                )
                RemindersEditor(alarms, allDay, zone) { alarms = it }
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
                    if (d.alarms.isNotEmpty()) requestNotifications()
                    val occurrence = existing?.occurrence
                    if (existing != null && occurrence != null) {
                        // Serie: alcance y resumen antes de aplicar nada.
                        // Cambiar la repetición no tiene sentido para una sola ocurrencia: "solo este" no se ofrece.
                        val ruleChanged = existing.before.repeat != d.repeat
                        askSeries(sheet, dialog, uiScope, existing.calendar, existing.uid, occurrence, zone, deleting = false, fields = changedFields(existing.before, d), ruleChanged = ruleChanged) { scope ->
                            CalendarApp.scope.launch { Events.updateSeries(existing.calendar, existing.uid, occurrence, scope, existing.before, d, zone) }
                            onSaved()
                        }
                        return@GlassButton
                    }
                    // Guardar sigue aunque se cierre la pantalla.
                    CalendarApp.scope.launch {
                        if (existing != null) Events.update(existing.calendar, existing.uid, existing.before, d, zone) else Events.create(c, d, zone)
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

/** Opción del selector de calendario: color, nombre y, tras un punto, celular o nube. */
@Composable
private fun CalendarChoice(c: CalendarInfo, selected: Boolean, onClick: () -> Unit) {
    val p = LocalGlass.current
    val content = if (selected) p.onAccent else p.content
    GlassSurface(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.RadioButton
                contentDescription = "${c.name}, ${originText(c)}"
            },
        shape = CircleShape,
        tint = if (selected) (if (p.solid) p.accent else p.accent.copy(alpha = if (p.dark) 0.5f else 0.92f)) else null,
        frost = 4.dp,
        shadowRadius = 6.dp,
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.color)))
            Spacer(Modifier.width(10.dp))
            Text(
                c.name,
                Modifier.weight(1f, fill = false),
                color = content,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(" · ", color = content.copy(alpha = 0.6f), fontSize = 15.sp)
            Icon(
                styled(if (c.local) Icons.Rounded.PhoneAndroid else Icons.Rounded.Cloud),
                null,
                Modifier.size(16.dp),
                tint = content.copy(alpha = 0.75f),
            )
        }
    }
}
