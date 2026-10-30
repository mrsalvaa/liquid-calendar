package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.AlarmSpec
import dev.liquidcalendar.core.ics.Trigger
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassChip
import dev.liquidcalendar.ui.glass.LocalDialog
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.util.numericDate
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import dev.liquidcalendar.util.timeOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** "15 minutos", "2 horas", "1 día", "1 semana". */
private fun amount(d: Duration): String {
    val m = d.abs().toMinutes()
    return when {
        m % (7 * 1440) == 0L && m > 0 -> plural(R.plurals.weeks, (m / (7 * 1440)).toInt())
        m % 1440 == 0L && m > 0 -> plural(R.plurals.days, (m / 1440).toInt())
        m % 60 == 0L && m > 0 -> plural(R.plurals.hours, (m / 60).toInt())
        else -> plural(R.plurals.minutes, m.toInt())
    }
}

/** Texto de un recordatorio, como lo diría una persona. */
fun alarmLabel(a: AlarmSpec, allDay: Boolean, zone: ZoneId): String = when (val t = a.trigger) {
    is Trigger.Absolute -> t.at.atZone(zone).let { str(R.string.alarm_at, "${numericDate(it.toLocalDate())} ${timeOf(it.toLocalTime())}") }
    is Trigger.Relative -> when {
        t.fromEnd && t.offset.isZero -> str(R.string.alarm_at_end)
        t.fromEnd -> str(if (t.offset.isNegative) R.string.alarm_before_end else R.string.alarm_after, amount(t.offset))
        allDay -> {
            // Todo el día: se cuenta desde la medianoche, así que se dice "N días antes a las HH:MM".
            val minutes = t.offset.toMinutes()
            val daysBefore = if (minutes >= 0) 0 else ((-minutes + 1439) / 1440).toInt()
            val time = LocalTime.MIDNIGHT.plusMinutes(minutes + daysBefore * 1440L)
            if (daysBefore == 0) str(R.string.alarm_on_day, timeOf(time)) else str(R.string.alarm_days_before_at, plural(R.plurals.days, daysBefore), timeOf(time))
        }
        t.offset.isZero -> str(R.string.alarm_at_start)
        t.offset.isNegative -> str(R.string.alarm_before, amount(t.offset))
        else -> str(R.string.alarm_after, amount(t.offset))
    }
}

/** Opciones para agregar: con hora, minutos u horas antes; de todo el día, a las 9 de ese día o de días antes. */
fun alarmPresets(allDay: Boolean): List<AlarmSpec> = if (allDay) {
    listOf(9L, -15L, -39L, -159L).map { AlarmSpec(Trigger.Relative(Duration.ofHours(it))) }
} else {
    listOf(0L, 5L, 10L, 15L, 30L, 60L, 120L, 1440L, 2880L, 10080L).map { AlarmSpec(Trigger.Relative(Duration.ofMinutes(-it))) }
}

/** Lista de recordatorios del formulario: tocar uno lo quita; "Agregar" ofrece las opciones. */
@Composable
fun RemindersEditor(alarms: List<AlarmSpec>, allDay: Boolean, zone: ZoneId, onChange: (List<AlarmSpec>) -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    Column {
        Text(str(R.string.reminders), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (alarms.isEmpty()) Text(str(R.string.no_reminders), Modifier.padding(vertical = 10.dp), fontSize = 13.sp, color = p.contentSecondary)
            alarms.forEachIndexed { i, a ->
                val label = alarmLabel(a, allDay, zone)
                GlassChip("$label  ✕", false, { onChange(alarms.filterIndexed { j, _ -> j != i }) }, Modifier.semantics { contentDescription = str(R.string.remove_reminder, label) })
            }
        }
        GlassButton(
            str(R.string.add_reminder),
            {
                dialog(
                    DialogSpec(str(R.string.add_reminder)) { dismiss ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            alarmPresets(allDay).filter { preset -> alarms.none { it.trigger == preset.trigger } }.forEach { preset ->
                                GlassChip(alarmLabel(preset, allDay, zone), false, {
                                    onChange(alarms + preset)
                                    dismiss()
                                })
                            }
                        }
                    },
                )
            },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            icon = Icons.Rounded.Add,
        )
    }
}
