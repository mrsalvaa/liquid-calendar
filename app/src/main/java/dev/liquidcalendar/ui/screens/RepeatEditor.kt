package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.Frequency
import dev.liquidcalendar.core.ics.RepeatEnd
import dev.liquidcalendar.core.ics.RepeatRule
import dev.liquidcalendar.core.ics.Repeats
import dev.liquidcalendar.core.ics.Repetition
import dev.liquidcalendar.ui.glass.GlassChip
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.util.dayInitial
import dev.liquidcalendar.util.dayName
import dev.liquidcalendar.util.dayShort
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.numericDate
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.liquidcalendar.ui.glass.GlassSurface

/** Tope del "cada N" y del "N veces": más que eso ya es una regla rara. */
private const val MAX_INTERVAL = 99
private const val MAX_COUNT = 999

/**
 * La repetición del evento, dentro del formulario: frecuencia, cada cuánto, qué días (semanal),
 * cómo (mensual) y hasta cuándo. Debajo, la regla en palabras.
 */
@Composable
fun RepeatEditor(
    repeat: Repetition,
    start: LocalDate,
    weekStart: DayOfWeek,
    onChange: (Repetition) -> Unit,
    onPickUntil: (LocalDate) -> Unit,
) {
    val p = LocalGlass.current
    val rule = (repeat as? Repetition.Rule)?.rule
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(R.string.repeat), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        // "Personalizada" solo aparece si la regla viene de otra app.
        val options = RepeatOption.entries.filter { it != RepeatOption.CUSTOM || repeat is Repetition.Custom }
        val selected = when (repeat) {
            Repetition.None -> RepeatOption.NONE
            is Repetition.Custom -> RepeatOption.CUSTOM
            is Repetition.Rule -> RepeatOption.of(repeat.rule.frequency)
        }
        ChipRow(options, selected, { str(it.label) }) { option ->
            val f = option.frequency
            when {
                option == RepeatOption.NONE -> onChange(Repetition.None)
                f == null || option == selected -> Unit
                // Cambiar de frecuencia conserva el intervalo y el final elegidos.
                else -> onChange(Repetition.Rule(Repeats.simple(f, start).copy(interval = rule?.interval ?: 1, end = rule?.end ?: RepeatEnd.Never)))
            }
        }

        if (repeat is Repetition.Custom) {
            Text(str(R.string.repeat_custom_hint), fontSize = 13.sp, color = p.contentSecondary)
        }
        if (rule == null) return@Column
        fun set(r: RepeatRule) = onChange(Repetition.Rule(r))

        // Cada N días / semanas / meses / años.
        Stepper(
            label = plural(unitPlural(rule.frequency), rule.interval, rule.interval),
            value = rule.interval,
            range = 1..MAX_INTERVAL,
            prefix = str(R.string.repeat_every),
        ) { set(rule.copy(interval = it)) }

        when (rule.frequency) {
            Frequency.WEEKLY -> Weekdays(rule.weekdays, weekStart) { days -> set(rule.copy(weekdays = days)) }
            Frequency.MONTHLY -> {
                val nth = Repeats.nthOfMonth(start)
                val choices = buildList {
                    add(null)
                    if (nth <= 4) add(nth to start.dayOfWeek)
                    if (Repeats.isLastOfMonth(start)) add(-1 to start.dayOfWeek)
                }
                ChipRow(choices, rule.nthWeekday, { monthlyLabel(it, start) }) { set(rule.copy(nthWeekday = it)) }
            }
            else -> Unit
        }

        // Hasta cuándo.
        Text(str(R.string.repeat_ends), Modifier.padding(top = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val endKinds = listOf(0, 1, 2)
        val endKind = when (rule.end) {
            RepeatEnd.Never -> 0
            is RepeatEnd.Until -> 1
            is RepeatEnd.Count -> 2
        }
        ChipRow(endKinds, endKind, { str(listOf(R.string.repeat_ends_never, R.string.repeat_ends_until, R.string.repeat_ends_count)[it]) }) { kind ->
            set(
                rule.copy(
                    end = when (kind) {
                        0 -> RepeatEnd.Never
                        1 -> RepeatEnd.Until(defaultUntil(rule.frequency, start))
                        else -> RepeatEnd.Count(10)
                    },
                ),
            )
        }
        when (val e = rule.end) {
            is RepeatEnd.Until -> GlassChip(longDate(e.date), false, { onPickUntil(e.date) })
            is RepeatEnd.Count -> Stepper(
                label = plural(R.plurals.repeat_times_unit, e.times, e.times),
                value = e.times,
                range = 1..MAX_COUNT,
            ) { set(rule.copy(end = RepeatEnd.Count(it))) }
            RepeatEnd.Never -> Unit
        }

        Text(
            describe(repeat, start),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 13.sp,
            color = p.contentSecondary,
        )
    }
}

/** Las opciones de la fila de arriba: no se repite, las cuatro frecuencias y la regla de otra app. */
private enum class RepeatOption(val label: Int, val frequency: Frequency?) {
    NONE(R.string.repeat_none, null),
    DAILY(R.string.repeat_daily, Frequency.DAILY),
    WEEKLY(R.string.repeat_weekly, Frequency.WEEKLY),
    MONTHLY(R.string.repeat_monthly, Frequency.MONTHLY),
    YEARLY(R.string.repeat_yearly, Frequency.YEARLY),
    CUSTOM(R.string.repeat_custom, null),
    ;

    companion object {
        fun of(f: Frequency) = entries.first { it.frequency == f }
    }
}

/** "−  2 semanas  +" con botones de 48 dp. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, prefix: String? = null, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (prefix != null) Text(prefix, fontSize = 15.sp)
        GlassIconButton(Icons.Rounded.Remove, str(R.string.repeat_less, label), { onChange(value - 1) }, size = 40.dp, enabled = value > range.first)
        Text(label, Modifier.widthIn(min = 96.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        GlassIconButton(Icons.Rounded.Add, str(R.string.repeat_more, label), { onChange(value + 1) }, size = 40.dp, enabled = value < range.last)
    }
}

/** Los siete días como chips redondos; siempre queda al menos uno elegido. */
@Composable
private fun Weekdays(selected: Set<DayOfWeek>, weekStart: DayOfWeek, onChange: (Set<DayOfWeek>) -> Unit) {
    val days = (0 until 7).map { weekStart.plus(it.toLong()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            val on = day in selected
            val toggle = {
                val next = if (on) selected - day else selected + day
                if (next.isNotEmpty()) onChange(next)
            }
            WeekdayChip(dayInitial(day), dayName(day), on, toggle, Modifier.weight(1f))
        }
    }
}

/**
 * Un día: la inicial en un círculo de cristal (con el acento si está elegido). TalkBack lo lee
 * como una casilla con el nombre completo ("lunes, marcado").
 */
@Composable
private fun WeekdayChip(initial: String, name: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    GlassSurface(
        modifier
            .height(44.dp)
            .clearAndSetSemantics {
                contentDescription = name
                role = Role.Checkbox
                toggleableState = ToggleableState(on)
                onClick { onClick(); true }
            },
        shape = CircleShape,
        tint = if (on) (if (p.solid) p.accent else p.accent.copy(alpha = if (p.dark) 0.5f else 0.92f)) else null,
        frost = 4.dp,
        shadowRadius = 6.dp,
        onClick = onClick,
    ) {
        Text(
            initial,
            Modifier.align(Alignment.Center),
            color = if (on) p.onAccent else p.content,
            fontSize = 14.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private fun frequencyLabel(f: Frequency) = when (f) {
    Frequency.DAILY -> R.string.repeat_daily
    Frequency.WEEKLY -> R.string.repeat_weekly
    Frequency.MONTHLY -> R.string.repeat_monthly
    Frequency.YEARLY -> R.string.repeat_yearly
}

private fun unitPlural(f: Frequency) = when (f) {
    Frequency.DAILY -> R.plurals.repeat_unit_days
    Frequency.WEEKLY -> R.plurals.repeat_unit_weeks
    Frequency.MONTHLY -> R.plurals.repeat_unit_months
    Frequency.YEARLY -> R.plurals.repeat_unit_years
}

/** Un final razonable al elegir "hasta una fecha": un mes, tres meses o un año después. */
private fun defaultUntil(f: Frequency, start: LocalDate): LocalDate = when (f) {
    Frequency.DAILY -> start.plusMonths(1)
    Frequency.WEEKLY -> start.plusMonths(3)
    else -> start.plusYears(1)
}

/** "El día 15" o "El tercer martes" / "El último viernes". */
private fun monthlyLabel(nth: Pair<Int, DayOfWeek>?, start: LocalDate): String =
    if (nth == null) str(R.string.repeat_on_day, start.dayOfMonth) else str(R.string.repeat_on_nth, ordinal(nth.first), dayName(nth.second).lowercase())

private fun ordinal(n: Int) = str(
    when (n) {
        1 -> R.string.repeat_nth_1
        2 -> R.string.repeat_nth_2
        3 -> R.string.repeat_nth_3
        4 -> R.string.repeat_nth_4
        else -> R.string.repeat_nth_last
    },
)

/** La repetición en palabras: "Cada 2 semanas: lun y mié, hasta el 31/12/2026". */
fun describe(repeat: Repetition, start: LocalDate): String = when (repeat) {
    Repetition.None -> str(R.string.repeat_none)
    is Repetition.Custom -> str(R.string.repeat_custom)
    is Repetition.Rule -> {
        val r = repeat.rule
        val every = if (r.interval == 1) str(frequencyLabel(r.frequency)) else plural(everyPlural(r.frequency), r.interval, r.interval)
        val detail = when (r.frequency) {
            Frequency.WEEKLY -> r.weekdays.sortedBy { (it.value - start.dayOfWeek.value + 7) % 7 }.joinToString(", ") { dayShort(it) }
            Frequency.MONTHLY -> monthlyLabel(r.nthWeekday, start).replaceFirstChar { it.lowercase() }
            Frequency.YEARLY -> "${start.dayOfMonth} ${start.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
            Frequency.DAILY -> null
        }
        val end = when (val e = r.end) {
            RepeatEnd.Never -> null
            is RepeatEnd.Until -> str(R.string.repeat_until_date, numericDate(e.date))
            is RepeatEnd.Count -> plural(R.plurals.repeat_times, e.times, e.times)
        }
        listOfNotNull(listOfNotNull(every, detail).joinToString(": "), end).joinToString(", ")
    }
}

private fun everyPlural(f: Frequency) = when (f) {
    Frequency.DAILY -> R.plurals.repeat_every_days
    Frequency.WEEKLY -> R.plurals.repeat_every_weeks
    Frequency.MONTHLY -> R.plurals.repeat_every_months
    Frequency.YEARLY -> R.plurals.repeat_every_years
}
