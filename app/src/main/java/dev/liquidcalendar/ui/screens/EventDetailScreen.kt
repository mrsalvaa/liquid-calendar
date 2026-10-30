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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.data.Editable
import dev.liquidcalendar.ui.glass.LocalDialog
import androidx.compose.runtime.rememberCoroutineScope
import dev.liquidcalendar.core.sync.ChangeState
import dev.liquidcalendar.data.EditBlock
import dev.liquidcalendar.data.Events
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.LocalActionSheet
import dev.liquidcalendar.ui.glass.SheetAction
import dev.liquidcalendar.ui.glass.SheetSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
fun EventDetailScreen(item: AgendaItem, clock: DeviceClock, padding: PaddingValues, onBack: () -> Unit, onEdit: (EditTarget) -> Unit) {
    val p = LocalGlass.current
    val occ = item.occurrence
    val sheet = LocalActionSheet.current
    val version by Repo.version.collectAsStateWithLifecycle()
    val editable by produceState<Pair<Editable?, EditBlock?>?>(null, version) { value = Events.editable(item, clock.zone) }
    val dialog = LocalDialog.current
    val uiScope = rememberCoroutineScope()
    val change by produceState<Repo.ChangeRow?>(null, version) {
        value = withContext(Dispatchers.IO) { Repo.changeFor(item.calendar.id, occ.uid) }
    }
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

        SectionLabel(str(R.string.reminders))
        GlassCard {
            val allDay = occ.time is When.AllDay
            if (occ.alarms.isEmpty()) Text(str(R.string.no_reminders), fontSize = 14.sp, color = p.contentSecondary)
            occ.alarms.forEach { Text(alarmLabel(it, allDay, clock.zone), fontSize = 15.sp) }
        }

        SectionLabel(str(R.string.calendar_label))
        GlassCard {
            CalendarBadge(item.calendar)
            if (item.calendar.readOnly) Text(str(R.string.read_only), fontSize = 12.sp, color = p.contentSecondary)
            change?.let { Text(changeStateText(it.change), Modifier.padding(top = 6.dp), fontSize = 13.sp, color = if (it.change.state == ChangeState.QUEUED) p.contentSecondary else p.danger) }
        }

        val e = editable
        when {
            e == null -> Unit
            e.second != null -> {
                // Por qué no se puede editar: siempre a la vista, no un botón que no hace nada.
                GlassCard(Modifier.padding(top = 12.dp)) {
                    Text(
                        str(
                            when (e.second) {
                                EditBlock.READ_ONLY -> R.string.read_only_calendar
                                EditBlock.UNRESOLVED -> R.string.unresolved_change
                                else -> R.string.err_generic
                            },
                        ),
                        fontSize = 14.sp,
                        color = p.contentSecondary,
                    )
                }
            }
            else -> Column {
                val ed = e.first!!
                if (ed.isSeries) {
                    GlassCard(Modifier.padding(top = 12.dp)) { Text(str(R.string.series_notice), fontSize = 14.sp, color = p.contentSecondary) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(str(R.string.edit), { onEdit(EditTarget.Existing(item.calendar, occ.uid, ed.draft, ed.occurrence)) }, Modifier.weight(1f), icon = Icons.Rounded.Edit)
                GlassButton(
                    str(R.string.delete),
                    {
                        val occurrence = ed.occurrence
                        if (occurrence != null) {
                            // Serie: alcance y resumen (cuántas ocurrencias, dónde) antes de borrar.
                            askSeries(sheet, dialog, uiScope, item.calendar, occ.uid, occurrence, clock.zone, deleting = true, fields = null) { scope ->
                                CalendarApp.scope.launch { Events.deleteSeries(item.calendar, occ.uid, occurrence, scope, occ.summary, clock.zone) }
                                onBack()
                            }
                            return@GlassButton
                        }
                        sheet(
                            SheetSpec(
                                title = str(R.string.delete_title),
                                // Siempre dice si se borra solo del celu o también del servidor.
                                subtitle = if (item.calendar.local) str(R.string.delete_local) else str(R.string.delete_synced, item.calendar.host.orEmpty()),
                                actions = listOf(
                                    SheetAction(str(R.string.delete_confirm), Icons.Rounded.DeleteOutline, danger = true) {
                                        CalendarApp.scope.launch { Events.delete(item.calendar, occ.uid, occ.summary) }
                                        onBack()
                                    },
                                ),
                            ),
                        )
                    },
                    Modifier.weight(1f),
                    icon = Icons.Rounded.DeleteOutline,
                    danger = true,
                )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
