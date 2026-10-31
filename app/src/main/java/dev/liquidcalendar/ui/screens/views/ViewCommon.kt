package dev.liquidcalendar.ui.screens.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.When
import dev.liquidcalendar.data.Agenda
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.ui.glass.GlassChip
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.styled
import dev.liquidcalendar.ui.glass.TitleTopPadding
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.str
import dev.liquidcalendar.util.timeOf
import java.time.LocalDate
import androidx.compose.runtime.LaunchedEffect
import dev.liquidcalendar.ui.glass.LocalTabActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Zona del dispositivo y fecha de hoy; se vuelven a leer al volver a la app (viajes, medianoche). */
data class DeviceClock(val zone: ZoneId, val today: LocalDate)

@Composable
fun rememberDeviceClock(): DeviceClock {
    var clock by remember { mutableStateOf(now()) }
    LifecycleResumeEffect(Unit) {
        clock = now()
        onPauseOrDispose {}
    }
    return clock
}

/** La hora actual, al minuto: se renueva cuando cambia el minuto (para lo que está "en curso"). */
@Composable
fun rememberNow(): java.time.Instant {
    var now by remember { mutableStateOf(java.time.Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000)
            now = java.time.Instant.now()
        }
    }
    return now
}

private fun now(): DeviceClock = ZoneId.systemDefault().let { DeviceClock(it, LocalDate.now(it)) }

/**
 * Ocurrencias de los calendarios visibles entre [first] y [lastInclusive] (días en la zona del
 * dispositivo). null mientras carga. Se recalcula al sincronizar o cambiar la visibilidad.
 */
@Composable
fun rememberAgenda(first: LocalDate, lastInclusive: LocalDate, zone: ZoneId): AgendaData? {
    val version by Repo.version.collectAsStateWithLifecycle()
    // Las pestañas que no se ven no calculan nada: conservan lo último y se ponen al día al volver.
    val active = LocalTabActive.current
    var data by remember { mutableStateOf<AgendaData?>(null) }
    LaunchedEffect(first, lastInclusive, zone, version, active) {
        if (!active && data != null) return@LaunchedEffect
        // Expandir y agrupar fuera del hilo de la interfaz.
        data = withContext(Dispatchers.Default) {
            val items = Agenda.load(first.atStartOfDay(zone).toInstant(), lastInclusive.plusDays(1).atStartOfDay(zone).toInstant(), zone)
            AgendaData(first, lastInclusive, items, byDay(items, first, lastInclusive, zone), byStart(items, first, zone))
        }
    }
    // Datos de otro rango (todavía calculando el nuevo): no se muestran.
    return data?.takeIf { it.first == first && it.last == lastInclusive }
}

/** Ocurrencias de un rango, agrupadas por cada día que ocupan y por el día en que empiezan. */
class AgendaData(
    val first: LocalDate,
    val last: LocalDate,
    val items: List<AgendaItem>,
    val perDay: Map<LocalDate, List<AgendaItem>>,
    /** Para la Agenda: cada evento una sola vez, el día en que empieza (o el primero del rango). */
    val byStart: List<Pair<LocalDate, List<AgendaItem>>>,
)

private fun byStart(items: List<AgendaItem>, first: LocalDate, zone: ZoneId): List<Pair<LocalDate, List<AgendaItem>>> =
    items.groupBy { maxOf(it.days(zone).start, first) }.toSortedMap().toList()

/** Ítems que ocupan cada día del rango. */
fun byDay(items: List<AgendaItem>, first: LocalDate, lastInclusive: LocalDate, zone: ZoneId): Map<LocalDate, List<AgendaItem>> {
    val map = HashMap<LocalDate, MutableList<AgendaItem>>()
    for (item in items) {
        val days = item.days(zone)
        var d = maxOf(days.start, first)
        val end = minOf(days.endInclusive, lastInclusive)
        while (d <= end) {
            map.getOrPut(d) { mutableListOf() } += item
            d = d.plusDays(1)
        }
    }
    return map
}

fun titleOf(item: AgendaItem): String = item.occurrence.summary ?: str(R.string.untitled_event)

/** Hora del ítem tal como se ve en [day]: "Todo el día", "10:00 – 11:00", o con fecha si cruza días. */
fun timeLabel(item: AgendaItem, day: LocalDate?, zone: ZoneId): String = when (val t = item.occurrence.time) {
    is When.AllDay -> str(R.string.all_day)
    is When.Timed -> {
        val s = t.start.atZone(zone)
        val e = t.end.atZone(zone)
        val sameDay = s.toLocalDate() == e.toLocalDate() || (e.toLocalTime() == java.time.LocalTime.MIDNIGHT && s.toLocalDate().plusDays(1) == e.toLocalDate())
        when {
            t.start == t.end -> timeOf(s.toLocalTime())
            sameDay -> str(R.string.time_range, timeOf(s.toLocalTime()), timeOf(e.toLocalTime()))
            else -> str(
                R.string.time_range,
                "${dev.liquidcalendar.util.shortDate(s.toLocalDate())} ${timeOf(s.toLocalTime())}",
                "${dev.liquidcalendar.util.shortDate(e.toLocalDate())} ${timeOf(e.toLocalTime())}",
            )
        }
    }
}

/** Lo que lee TalkBack de un evento: título, hora y calendario (y si es local o sincronizado). */
fun spokenItem(item: AgendaItem, day: LocalDate?, zone: ZoneId): String =
    str(R.string.event_summary, titleOf(item), timeLabel(item, day, zone), "${item.calendar.name}, ${originText(item.calendar)}")

fun originText(c: CalendarInfo): String = when {
    c.local -> str(R.string.local_badge)
    c.subscription -> str(R.string.subscription_badge, c.host.orEmpty())
    c.missing -> str(R.string.missing_badge)
    else -> str(R.string.synced_badge, c.host.orEmpty())
}

/** Ícono y texto que dicen si un calendario vive solo en el celu o se sincroniza con un servidor. */
@Composable
fun CalendarBadge(c: CalendarInfo, modifier: Modifier = Modifier, showName: Boolean = true) {
    val p = LocalGlass.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showName) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
            Spacer(Modifier.width(8.dp))
            Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
        }
        OriginIcon(c)
        Spacer(Modifier.width(4.dp))
        Text(originText(c), fontSize = 12.sp, color = p.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ícono del origen de un calendario: dispositivo, nube (sincronizado) o feed (suscripción). */
@Composable
fun OriginIcon(c: CalendarInfo, size: Int = 14) {
    Icon(
        styled(
            when {
                c.local -> Icons.Rounded.PhoneAndroid
                c.subscription -> Icons.Rounded.RssFeed
                c.missing -> Icons.Rounded.CloudOff
                else -> Icons.Rounded.Cloud
            },
        ),
        null,
        Modifier.size(size.dp),
        tint = LocalGlass.current.contentSecondary,
    )
}

/** Celu = local; nube = sincronizado. La descripción va en el texto al lado, no en el ícono. */
@Composable
fun OriginIcon(local: Boolean, size: Int = 14) {
    Icon(
        styled(if (local) Icons.Rounded.PhoneAndroid else Icons.Rounded.Cloud),
        null,
        Modifier.size(size.dp),
        tint = LocalGlass.current.contentSecondary,
    )
}

/** Cabecera de cada vista: título del período, anterior/siguiente, Hoy, Calendarios y Ajustes. */
@Composable
fun PeriodHeader(
    title: String,
    subtitle: String?,
    prevLabel: String,
    nextLabel: String,
    showToday: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onCalendars: () -> Unit,
    onSettings: () -> Unit,
) {
    val p = LocalGlass.current
    Column(Modifier.fillMaxWidth().padding(top = TitleTopPadding, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Se achica si no entra (p. ej. "25/10 – 31/10/2026"), en vez de cortarse con "…".
                BasicText(
                    title,
                    Modifier.semantics { heading() },
                    style = TextStyle(color = p.content, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp),
                )
                if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = p.contentSecondary, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassIconButton(Icons.Rounded.Layers, str(R.string.calendars), onCalendars, size = 44.dp)
                GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), onSettings, size = 44.dp)
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, prevLabel, onPrev, size = 40.dp)
            GlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, nextLabel, onNext, size = 40.dp)
            if (showToday) GlassChip(str(R.string.today), false, onToday)
        }
    }
}

fun dayHeading(day: LocalDate, today: LocalDate): String =
    longDate(day) + if (day == today) " (${str(R.string.today_marker)})" else ""
