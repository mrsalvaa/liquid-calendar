package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.When
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SectionLabel
import dev.liquidcalendar.ui.screens.views.CalendarBadge
import dev.liquidcalendar.ui.screens.views.DeviceClock
import dev.liquidcalendar.ui.screens.views.titleOf
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.str
import dev.liquidcalendar.util.timeOf
import java.time.format.TextStyle
import java.util.Locale

/** Detalle de una ocurrencia (solo lectura). */
@Composable
fun EventDetailScreen(item: AgendaItem, clock: DeviceClock, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val occ = item.occurrence
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        BackTitle(titleOf(item), onBack)

        GlassCard {
            when (val t = occ.time) {
                is When.AllDay -> {
                    val lastDay = t.endExclusive.minusDays(1)
                    Text(
                        if (lastDay == t.first) longDate(t.first) else str(R.string.date_range, longDate(t.first), longDate(lastDay)),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(str(R.string.all_day), fontSize = 14.sp, color = p.contentSecondary)
                }
                is When.Timed -> {
                    val s = t.start.atZone(clock.zone)
                    val e = t.end.atZone(clock.zone)
                    if (s.toLocalDate() == e.toLocalDate() || t.start == t.end) {
                        Text(longDate(s.toLocalDate()), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (t.start == t.end) timeOf(s.toLocalTime()) else str(R.string.time_range, timeOf(s.toLocalTime()), timeOf(e.toLocalTime())),
                            fontSize = 14.sp,
                            color = p.contentSecondary,
                        )
                    } else {
                        Text(str(R.string.time_range, "${longDate(s.toLocalDate())} ${timeOf(s.toLocalTime())}", ""), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("${longDate(e.toLocalDate())} ${timeOf(e.toLocalTime())}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    // Las horas se muestran siempre en la zona del dispositivo; se dice cuál es.
                    Text(
                        str(R.string.times_in_zone, clock.zone.getDisplayName(TextStyle.FULL, Locale.getDefault())),
                        Modifier.padding(top = 4.dp),
                        fontSize = 12.sp,
                        color = p.contentSecondary,
                    )
                }
            }
            if (occ.recurring) Text(str(R.string.recurring), Modifier.padding(top = 6.dp), fontSize = 14.sp, color = p.accentFg)
        }

        if (occ.location != null) {
            SectionLabel(str(R.string.location))
            GlassCard { SelectionContainer { Text(occ.location!!, fontSize = 15.sp) } }
        }
        if (occ.description != null) {
            SectionLabel(str(R.string.description))
            GlassCard { SelectionContainer { Text(occ.description!!, fontSize = 15.sp) } }
        }

        SectionLabel(str(R.string.calendar_label))
        GlassCard {
            CalendarBadge(item.calendar)
            if (item.calendar.readOnly) Text(str(R.string.read_only), fontSize = 12.sp, color = p.contentSecondary)
        }
        Spacer(Modifier.height(8.dp))
    }
}
