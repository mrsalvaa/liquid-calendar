package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.WheelColon
import dev.liquidcalendar.ui.glass.WheelPicker
import dev.liquidcalendar.ui.screens.views.monthGridStart
import dev.liquidcalendar.util.dayInitial
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.monthTitle
import dev.liquidcalendar.util.str
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Mes chico para elegir un día, con la semana desde el día configurado. */
@Composable
fun DatePickerContent(initial: LocalDate, weekStart: DayOfWeek, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val p = LocalGlass.current
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monthTitle(month.atDay(1)), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            GlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, str(R.string.prev_month), { month = month.minusMonths(1) }, size = 40.dp)
            GlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, str(R.string.next_month), { month = month.plusMonths(1) }, Modifier.padding(start = 8.dp), size = 40.dp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clearAndSetSemantics { }) {
            (0 until 7).forEach { i ->
                Text(dayInitial(weekStart.plus(i.toLong())), Modifier.weight(1f), fontSize = 12.sp, color = p.contentSecondary, textAlign = TextAlign.Center)
            }
        }
        val first = monthGridStart(month, weekStart)
        (0 until 6).forEach { w ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { d ->
                    val day = first.plusDays((w * 7 + d).toLong())
                    val isSelected = day == initial
                    val inMonth = YearMonth.from(day) == month
                    val label = longDate(day)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) p.accent else Color.Transparent)
                            .clickable { onPick(day) }
                            .clearAndSetSemantics {
                                contentDescription = label
                                role = Role.Button
                                selected = isSelected
                                onClick { onPick(day); true }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            fontSize = 14.sp,
                            fontWeight = if (day == today || isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isSelected -> p.onAccent
                                day == today -> p.accentFg
                                inMonth -> p.content
                                else -> p.contentSecondary.copy(alpha = 0.45f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Horas y minutos (de a 5) con las ruedas de Liquid Clock. */
@Composable
fun TimePickerContent(initial: LocalTime, onDone: (LocalTime) -> Unit) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minuteStep by remember { mutableIntStateOf(initial.minute / 5) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(count = 24, value = hour, onValueChange = { hour = it })
            WheelColon()
            WheelPicker(
                count = 12,
                value = minuteStep,
                onValueChange = { minuteStep = it },
                label = { "%02d".format(it * 5) },
                // Se escriben minutos: van al múltiplo de 5 más cercano (58 queda en 55).
                typed = { m -> if (m < 60) minOf((m + 2) / 5, 11) else null },
            )
        }
        GlassButton(str(R.string.done), { onDone(LocalTime.of(hour, minuteStep * 5)) }, Modifier.fillMaxWidth().padding(top = 16.dp), prominent = true)
    }
}
