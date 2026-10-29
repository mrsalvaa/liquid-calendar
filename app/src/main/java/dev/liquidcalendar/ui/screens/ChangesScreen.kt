package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.DraftTime
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.IcsWriter
import dev.liquidcalendar.core.sync.ChangeKind
import dev.liquidcalendar.core.sync.ChangeState
import dev.liquidcalendar.core.sync.PendingChange
import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.sync.SyncScheduler
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.LocalActionSheet
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SheetAction
import dev.liquidcalendar.ui.glass.SheetSpec
import dev.liquidcalendar.ui.screens.views.CalendarBadge
import dev.liquidcalendar.ui.screens.views.DeviceClock
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.str
import dev.liquidcalendar.util.timeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Estado de un cambio, para mostrarlo (sin URLs ni detalles técnicos). */
fun changeStateText(c: PendingChange): String = when (c.state) {
    ChangeState.QUEUED -> str(R.string.state_queued)
    ChangeState.CONFLICT -> str(R.string.state_conflict)
    ChangeState.FAILED -> str(R.string.state_failed, errorMessage(c.error))
}

/** Cola de cambios: qué falta subir, qué está en conflicto o falló, y qué hacer con cada uno. */
@Composable
fun ChangesScreen(clock: DeviceClock, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val version by Repo.version.collectAsStateWithLifecycle()
    val data by produceState<Pair<List<Repo.ChangeRow>, Map<Long, CalendarInfo>>?>(null, version) {
        value = withContext(Dispatchers.IO) { Repo.changes() to Repo.calendarInfos() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        BackTitle(str(R.string.pending_changes), onBack)
        val (rows, calendars) = data ?: return@Column
        if (rows.isEmpty()) EmptyState(str(R.string.pending_changes), str(R.string.no_pending_changes))
        rows.forEach { row ->
            val calendar = calendars[row.calendarId] ?: return@forEach
            ChangeCard(row, calendar, clock.zone)
        }
        Text("", Modifier.padding(bottom = 8.dp), color = p.contentSecondary)
    }
}

@Composable
private fun ChangeCard(row: Repo.ChangeRow, calendar: CalendarInfo, zone: ZoneId) {
    val p = LocalGlass.current
    val sheet = LocalActionSheet.current
    val c = row.change
    GlassCard(Modifier.padding(bottom = 10.dp)) {
        Text(
            "${str(when (c.kind) { ChangeKind.CREATE -> R.string.change_create; ChangeKind.UPDATE -> R.string.change_update; ChangeKind.DELETE -> R.string.change_delete })} · ${row.summary ?: str(R.string.untitled_event)}",
            Modifier.semantics { heading() },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        CalendarBadge(calendar, Modifier.padding(top = 4.dp))
        Text(changeStateText(c), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = if (c.state == ChangeState.QUEUED) p.contentSecondary else p.danger)

        if (c.state == ChangeState.CONFLICT) {
            // Las dos versiones, para elegir con la información a la vista.
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Version(str(R.string.mine), if (c.kind == ChangeKind.DELETE) null else c.ics, str(R.string.deleted_here), zone, Modifier.weight(1f))
                Version(str(R.string.theirs), c.serverIcs, str(R.string.deleted_on_server), zone, Modifier.weight(1f))
            }
            GlassButton(str(R.string.keep_mine), {
                CalendarApp.scope.launch(Dispatchers.IO) { Repo.keepMine(c.id); SyncScheduler.pushSoon() }
            }, Modifier.fillMaxWidth().padding(top = 12.dp), prominent = true)
            GlassButton(str(R.string.keep_theirs), {
                CalendarApp.scope.launch(Dispatchers.IO) { Repo.keepServer(c.id) }
            }, Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(str(R.string.decide_later), Modifier.padding(top = 8.dp), fontSize = 12.sp, color = p.contentSecondary)
        } else {
            GlassButton(str(R.string.discard), {
                sheet(
                    SheetSpec(
                        title = str(R.string.discard_title),
                        subtitle = str(R.string.discard_text),
                        actions = listOf(SheetAction(str(R.string.discard), Icons.Rounded.DeleteOutline, danger = true) {
                            CalendarApp.scope.launch(Dispatchers.IO) { Repo.discard(c.id); SyncScheduler.pushSoon() }
                        }),
                    ),
                )
            }, Modifier.fillMaxWidth().padding(top = 12.dp), danger = true)
        }
    }
}

/** Una versión del evento en pocas líneas: título, fecha y hora, ubicación. */
@Composable
private fun Version(label: String, ics: String?, deletedText: String, zone: ZoneId, modifier: Modifier) {
    val p = LocalGlass.current
    val lines = remember(ics) {
        if (ics == null) {
            listOf(deletedText)
        } else {
            try {
                val r = Ics.parseResource(ics)
                val d = IcsWriter.draftOf(r, r.uid.orEmpty(), zone)
                if (d == null) {
                    listOf(deletedText)
                } else {
                    val time = when (val t = d.time) {
                        is DraftTime.AllDay -> "${longDate(t.first)} · ${str(R.string.all_day)}"
                        is DraftTime.Timed -> t.start.atZone(zone).let { s -> "${longDate(s.toLocalDate())} · ${timeOf(s.toLocalTime())} – ${timeOf(t.end.atZone(zone).toLocalTime())}" }
                    }
                    listOfNotNull(d.summary.ifBlank { str(R.string.untitled_event) }, time, d.location)
                }
            } catch (e: IcsException) {
                listOf(str(R.string.err_generic))
            }
        }
    }
    Column(modifier) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.accentFg)
        lines.forEach { Text(it, fontSize = 13.sp) }
    }
}
