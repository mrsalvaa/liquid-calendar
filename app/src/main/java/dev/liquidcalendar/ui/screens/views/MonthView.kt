package dev.liquidcalendar.ui.screens.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.data.Agenda
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.ui.glass.LocalTabActive
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.LocalReducedMotion
import dev.liquidcalendar.util.dayInitial
import dev.liquidcalendar.util.plural
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** Primer día visible de la cuadrícula del mes: el inicio de la semana que contiene el día 1. */
fun monthGridStart(month: YearMonth, weekStart: DayOfWeek): LocalDate =
    month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))

/**
 * Mes en una cuadrícula de 6 semanas. Se cambia de mes deslizando arriba/abajo (el
 * deslizamiento horizontal es para cambiar de pestaña) o con las flechas de la cabecera.
 */
@Composable
fun MonthView(
    month: YearMonth,
    weekStart: DayOfWeek,
    clock: DeviceClock,
    onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val change by rememberUpdatedState(onMonth)
    val current by rememberUpdatedState(month)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val active = LocalTabActive.current
    // Con el mes ya en pantalla, se dejan calculados el anterior y el siguiente (quedan en la
    // caché de Agenda): al deslizar, la cuadrícula aparece completa.
    LaunchedEffect(month, weekStart, clock.zone, active) {
        if (!active) return@LaunchedEffect
        delay(350)
        for (m in listOf(month.plusMonths(1), month.minusMonths(1))) {
            val first = monthGridStart(m, weekStart)
            Agenda.load(first.atStartOfDay(clock.zone).toInstant(), first.plusDays(42).atStartOfDay(clock.zone).toInstant(), clock.zone)
        }
    }

    Column(
        modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    if (abs(drag) > threshold) change(if (drag < 0) current.plusMonths(1) else current.minusMonths(1))
                    drag = 0f
                },
            ) { change, dy ->
                change.consume()
                drag += dy
            }
        },
    ) {
        WeekdayHeader(weekStart)
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                if (reduced) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    val forward = targetState > initialState
                    (slideInVertically(tween(260)) { h -> if (forward) h / 4 else -h / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(200)) { h -> if (forward) -h / 4 else h / 4 } + fadeOut(tween(160)))
                }
            },
            label = "month",
            modifier = Modifier.fillMaxSize(),
        ) { m -> MonthGrid(m, weekStart, clock, onDay) }
    }
}

@Composable
private fun WeekdayHeader(weekStart: DayOfWeek) {
    val p = LocalGlass.current
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clearAndSetSemantics { }) {
        (0 until 7).forEach { i ->
            Text(
                dayInitial(weekStart.plus(i.toLong())),
                Modifier.weight(1f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.contentSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, weekStart: DayOfWeek, clock: DeviceClock, onDay: (LocalDate) -> Unit) {
    val first = monthGridStart(month, weekStart)
    val last = first.plusDays(41)
    val perDay = rememberAgenda(first, last, clock.zone)?.perDay.orEmpty()
    Column(Modifier.fillMaxSize()) {
        (0 until 6).forEach { week ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                (0 until 7).forEach { d ->
                    val day = first.plusDays((week * 7 + d).toLong())
                    DayCell(day, inMonth = YearMonth.from(day) == month, clock.today, perDay[day].orEmpty(), onDay, Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, inMonth: Boolean, todayDate: LocalDate, items: List<AgendaItem>, onDay: (LocalDate) -> Unit, modifier: Modifier) {
    val p = LocalGlass.current
    val today = day == todayDate
    val spoken = plural(R.plurals.day_summary, items.size, dayHeading(day, todayDate), items.size)
    Column(
        modifier
            .padding(1.5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (today) p.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onDay(day) }
            // TalkBack: una sola parada por día con la fecha y la cantidad de eventos.
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onDay(day); true }
            }
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (today) p.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (today) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    today -> p.onAccent
                    inMonth -> p.content
                    else -> p.contentSecondary.copy(alpha = 0.45f)
                },
            )
        }
        if (items.isNotEmpty()) DayChips(items, dim = !inMonth, Modifier.fillMaxWidth().weight(1f))
    }
}

/**
 * Los eventos de un día dibujados en un solo Canvas (rectángulo de color + título), en vez de un
 * componente por evento: con cientos de series la cuadrícula se arma mucho más rápido. Lo que
 * lee TalkBack es el resumen del día, así que no se pierde nada.
 */
@Composable
private fun DayChips(items: List<AgendaItem>, dim: Boolean, modifier: Modifier) {
    val p = LocalGlass.current
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val titleStyle = TextStyle(fontSize = 10.sp, color = if (dim) p.contentSecondary else p.content)
    val moreStyle = TextStyle(fontSize = 9.sp, color = p.contentSecondary)
    val more = if (items.size > MAX_CHIPS) plural(R.plurals.more_events, items.size - MAX_CHIPS) else null
    val titles = remember(items) { items.take(MAX_CHIPS).map { titleOf(it) to Color(it.calendar.color) } }
    val alpha = if (dim) 0.18f else if (p.dark) 0.32f else 0.24f
    Canvas(modifier.padding(top = 2.dp)) {
        val chip = 15.dp.toPx()
        val gap = 2.dp.toPx()
        val pad = 3.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        var y = 0f
        for ((title, color) in titles) {
            if (y + chip > size.height) break
            drawRoundRect(color.copy(alpha = alpha), Offset(0f, y), Size(size.width, chip), radius)
            val layout = measurer.measure(
                title, titleStyle, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
                constraints = Constraints(maxWidth = (size.width - pad * 2).toInt().coerceAtLeast(0)),
            )
            drawText(layout, topLeft = Offset(pad, y + (chip - layout.size.height) / 2f))
            y += chip + gap
        }
        if (more != null && y + 10.dp.toPx() <= size.height) {
            val layout = measurer.measure(more, moreStyle, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(0)))
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, y))
        }
    }
}

private const val MAX_CHIPS = 3
