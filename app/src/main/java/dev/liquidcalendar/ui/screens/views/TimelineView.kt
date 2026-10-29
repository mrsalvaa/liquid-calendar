package dev.liquidcalendar.ui.screens.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.When
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.LocalReducedMotion
import dev.liquidcalendar.util.dayShort
import dev.liquidcalendar.util.hourLabel
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val HOUR = 56.dp
private val GUTTER = 44.dp

/** Semana (7 días) o día (1): franja de todo el día arriba y horas debajo. */
@Composable
fun TimelineView(days: List<LocalDate>, clock: DeviceClock, onEvent: (AgendaItem) -> Unit, onDay: ((LocalDate) -> Unit)?, modifier: Modifier = Modifier) {
    val first = days.first()
    val last = days.last()
    val perDay = rememberAgenda(first, last, clock.zone)?.perDay.orEmpty()
    Column(modifier) {
        if (days.size > 1) DayHeaders(days, clock.today, perDay, onDay)
        AllDayStrip(days, perDay, clock.zone, onEvent)
        HoursGrid(days, perDay, clock, onEvent, Modifier.weight(1f))
    }
}

@Composable
private fun DayHeaders(days: List<LocalDate>, today: LocalDate, perDay: Map<LocalDate, List<AgendaItem>>, onDay: ((LocalDate) -> Unit)?) {
    val p = LocalGlass.current
    Row(Modifier.fillMaxWidth().padding(start = GUTTER, bottom = 4.dp)) {
        days.forEach { d ->
            val isToday = d == today
            val count = perDay[d]?.size ?: 0
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (onDay != null) Modifier.clickable { onDay(d) } else Modifier)
                    .clearAndSetSemantics {
                        contentDescription = plural(R.plurals.day_summary, count, dayHeading(d, today), count)
                        if (onDay != null) {
                            role = Role.Button
                            onClick { onDay(d); true }
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(dayShort(d.dayOfWeek), fontSize = 11.sp, color = p.contentSecondary)
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (isToday) p.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                    Text(d.dayOfMonth.toString(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (isToday) p.onAccent else p.content)
                }
            }
        }
    }
}

@Composable
private fun AllDayStrip(days: List<LocalDate>, perDay: Map<LocalDate, List<AgendaItem>>, zone: ZoneId, onEvent: (AgendaItem) -> Unit) {
    val p = LocalGlass.current
    val allDay = days.associateWith { d -> perDay[d].orEmpty().filter { it.occurrence.time is When.AllDay } }
    if (allDay.values.all { it.isEmpty() }) return
    val max = if (days.size == 1) 6 else 2
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            str(R.string.all_day),
            Modifier.width(GUTTER).padding(top = 3.dp),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = p.contentSecondary,
        )
        days.forEach { d ->
            Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                val list = allDay[d].orEmpty()
                list.take(max).forEach { item -> Block(item, d, zone, onEvent, Modifier.fillMaxWidth().height(20.dp).padding(bottom = 2.dp)) }
                if (list.size > max) Text(plural(R.plurals.more_events, list.size - max), fontSize = 9.sp, color = p.contentSecondary)
            }
        }
    }
}

@Composable
private fun HoursGrid(days: List<LocalDate>, perDay: Map<LocalDate, List<AgendaItem>>, clock: DeviceClock, onEvent: (AgendaItem) -> Unit, modifier: Modifier) {
    val p = LocalGlass.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    // Al abrir: a la hora actual si se ve hoy, si no a las 7.
    LaunchedEffect(days.first(), days.size) {
        val hour = if (clock.today in days) (LocalTime.now(clock.zone).hour - 1).coerceIn(0, 20) else 7
        val y = with(density) { (HOUR * hour).roundToPx() }
        if (reduced) scroll.scrollTo(y) else scroll.animateScrollTo(y)
    }
    Box(modifier.fillMaxWidth().verticalScroll(scroll)) {
        val line = p.content.copy(alpha = if (p.dark) 0.08f else 0.1f)
        Canvas(Modifier.fillMaxWidth().height(HOUR * 24)) {
            for (h in 0..24) {
                val y = HOUR.toPx() * h
                drawLine(line, Offset(GUTTER.toPx(), y), Offset(size.width, y), strokeWidth = 1f)
            }
        }
        Column(Modifier.width(GUTTER).clearAndSetSemantics { }) {
            (0 until 24).forEach { h ->
                Text(
                    if (h == 0) "" else hourLabel(h),
                    Modifier.height(HOUR).offset(y = (-7).dp).padding(end = 6.dp).fillMaxWidth(),
                    fontSize = 10.sp,
                    color = p.contentSecondary,
                    textAlign = TextAlign.End,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = GUTTER).height(HOUR * 24)) {
            days.forEach { d ->
                BoxWithConstraints(Modifier.weight(1f).fillMaxSize().padding(horizontal = 1.dp)) {
                    val timed = perDay[d].orEmpty().filter { it.occurrence.time is When.Timed }
                    val placed = remember(timed, d, clock.zone) { layoutDay(timed, d, clock.zone) }
                    placed.forEach { pl ->
                        val laneWidth: Dp = maxWidth / pl.lanes
                        Block(
                            pl.item, d, clock.zone, onEvent,
                            Modifier
                                .offset(x = laneWidth * pl.lane, y = HOUR * (pl.startMin / 60f))
                                .width(laneWidth - 1.dp)
                                .height(maxOf(HOUR * ((pl.endMin - pl.startMin) / 60f), 22.dp)),
                            showTime = true,
                        )
                    }
                    if (d == clock.today) NowLine(clock.zone)
                }
            }
        }
    }
}

@Composable
private fun NowLine(zone: ZoneId) {
    val p = LocalGlass.current
    val now = LocalTime.now(zone)
    val y = HOUR * ((now.hour * 60 + now.minute) / 60f)
    Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(p.danger).clearAndSetSemantics { })
    Box(Modifier.offset(x = (-4).dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(p.danger).clearAndSetSemantics { })
}

/** Un evento en la grilla: color de su calendario, título y (si entra) la hora. */
@Composable
private fun Block(item: AgendaItem, day: LocalDate, zone: ZoneId, onEvent: (AgendaItem) -> Unit, modifier: Modifier, showTime: Boolean = false) {
    val p = LocalGlass.current
    val color = Color(item.calendar.color)
    val spoken = spokenItem(item, day, zone)
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (p.dark) 0.34f else 0.26f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .clickable { onEvent(item) }
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onEvent(item); true }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(titleOf(item), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (showTime) Text(timeLabel(item, day, zone), fontSize = 10.sp, lineHeight = 12.sp, color = p.contentSecondary, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

private class Placed(val item: AgendaItem, val startMin: Int, val endMin: Int, var lane: Int = 0, var lanes: Int = 1)

/**
 * Ubica los eventos con hora de un día: minutos desde las 0:00 (recortados al día) y carriles
 * para los que se superponen, como en cualquier calendario.
 */
private fun layoutDay(items: List<AgendaItem>, day: LocalDate, zone: ZoneId): List<Placed> {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
    val minutesInDay = Duration.between(dayStart, dayEnd).toMinutes().toInt() // 1380 o 1500 en cambios de hora
    fun minutes(i: Instant): Int = (Duration.between(dayStart, i).toMinutes().toInt() * 1440 / minutesInDay).coerceIn(0, 1440)
    val placed = items.map { item ->
        val t = item.occurrence.time as When.Timed
        val s = minutes(maxOf(t.start, dayStart))
        val e = maxOf(minutes(minOf(t.end, dayEnd)), s + 20)
        Placed(item, s, e.coerceAtMost(1440))
    }.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
    // Grupos de eventos que se tocan; dentro de cada grupo, el primer carril libre.
    var group = mutableListOf<Placed>()
    var groupEnd = -1
    fun close() {
        val n = (group.maxOfOrNull { it.lane } ?: 0) + 1
        group.forEach { it.lanes = n }
        group = mutableListOf()
    }
    for (pl in placed) {
        if (pl.startMin >= groupEnd && group.isNotEmpty()) close()
        val laneEnds = IntArray(group.size + 1) { -1 }
        group.forEach { g -> if (g.endMin > pl.startMin) laneEnds[g.lane] = maxOf(laneEnds[g.lane], g.endMin) }
        pl.lane = laneEnds.indexOfFirst { it == -1 }
        group += pl
        groupEnd = maxOf(groupEnd, pl.endMin)
    }
    if (group.isNotEmpty()) close()
    return placed
}
