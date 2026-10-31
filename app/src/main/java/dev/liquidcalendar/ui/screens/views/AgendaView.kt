package dev.liquidcalendar.ui.screens.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.ui.glass.GlassSurface
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.screens.EmptyState
import dev.liquidcalendar.util.str
import java.time.LocalDate

/**
 * Los eventos de [DAYS] días desde [from], agrupados por día. Si [from] es hoy, lo que está en
 * curso va primero, en "Ahora", y ya no se repite en su día.
 */
@Composable
fun AgendaView(from: LocalDate, clock: DeviceClock, onEvent: (AgendaItem) -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    val data = rememberAgenda(from, from.plusDays(DAYS - 1L), clock.zone)
    val items = data?.items
    val now = rememberNow()
    val ongoing = if (from == clock.today) items.orEmpty().filter { it.isOngoing(now) } else emptyList()
    val groups = data?.byStart.orEmpty()
        .map { (day, list) -> day to list.filter { it !in ongoing } }
        .filter { it.second.isNotEmpty() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (items != null && groups.isEmpty() && ongoing.isEmpty()) {
            item { EmptyState(str(R.string.no_events_day), str(R.string.agenda_empty)) }
        }
        if (ongoing.isNotEmpty()) {
            item(key = "now") { DayHeading(str(R.string.now), accent = true) }
            items(ongoing, key = { "n${it.occurrence.key}${it.calendar.id}" }) { item -> AgendaRow(item, clock.today, clock, onEvent) }
        }
        groups.forEach { (day, list) ->
            item(key = "d$day") { DayHeading(dayHeading(day, clock.today), accent = day == clock.today) }
            items(list, key = { "e$day${it.occurrence.key}${it.calendar.id}" }) { item -> AgendaRow(item, day, clock, onEvent) }
        }
    }
}

@Composable
private fun DayHeading(text: String, accent: Boolean) {
    val p = LocalGlass.current
    Text(
        text,
        Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (accent) p.accentFg else p.contentSecondary,
    )
}

@Composable
private fun AgendaRow(item: AgendaItem, day: LocalDate, clock: DeviceClock, onEvent: (AgendaItem) -> Unit) {
    val p = LocalGlass.current
    val spoken = spokenItem(item, day, clock.zone)
    GlassSurface(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onEvent(item); true }
            },
        shape = RoundedCornerShape(20.dp),
        frost = 10.dp,
        shadowRadius = 8.dp,
        onClick = { onEvent(item) },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Color(item.calendar.color)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(titleOf(item), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timeLabel(item, day, clock.zone), fontSize = 13.sp, color = p.contentSecondary, maxLines = 1)
                CalendarBadge(item.calendar, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Días que muestra la agenda, contando el primero (como el widget). */
private const val DAYS = 14
