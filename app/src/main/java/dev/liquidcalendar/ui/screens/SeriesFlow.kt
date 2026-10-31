package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.EventDraft
import dev.liquidcalendar.core.ics.Moment
import dev.liquidcalendar.core.ics.SeriesScope
import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Events
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.SheetAction
import dev.liquidcalendar.ui.glass.SheetSpec
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Qué campos cambian, para el resumen ("título, horario"). null si no cambió nada. */
fun changedFields(before: EventDraft, after: EventDraft): String? = listOfNotNull(
    R.string.field_title.takeIf { before.summary != after.summary },
    R.string.field_time.takeIf { before.time != after.time },
    R.string.field_location.takeIf { before.location != after.location },
    R.string.field_description.takeIf { before.description != after.description },
    R.string.field_alarms.takeIf { before.alarms != after.alarms },
    R.string.field_repeat.takeIf { before.repeat != after.repeat },
).map(::str).takeIf { it.isNotEmpty() }?.joinToString(", ")

/**
 * Antes de tocar una serie: elegir el alcance y ver un resumen (qué cambia, cuántas ocurrencias,
 * dónde) para confirmar. Nada se aplica sin la confirmación.
 */
fun askSeries(
    sheet: (SheetSpec) -> Unit,
    dialog: (DialogSpec) -> Unit,
    scope: CoroutineScope,
    calendar: CalendarInfo,
    uid: String,
    occurrence: Moment,
    zone: ZoneId,
    deleting: Boolean,
    fields: String?,
    ruleChanged: Boolean = false,
    onConfirm: (SeriesScope) -> Unit,
) {
    fun summary(s: SeriesScope) = scope.launch {
        val count = Events.affected(calendar, uid, occurrence, s, zone)
        // Cambiar la regla descarta los cambios hechos a ocurrencias sueltas (eran de la regla vieja).
        val discarded = if (ruleChanged) Events.discardedByRuleChange(calendar, uid, occurrence, s, zone) else 0
        val lines = listOfNotNull(
            if (deleting) null else fields?.let { str(R.string.summary_fields, it) } ?: str(R.string.summary_no_changes),
            if (count == null) str(R.string.summary_endless) else plural(R.plurals.summary_affected, count),
            plural(R.plurals.summary_discarded, discarded, discarded).takeIf { discarded > 0 },
            str(R.string.summary_split).takeIf { s == SeriesScope.FOLLOWING && !deleting },
            if (calendar.local) str(R.string.where_local) else str(R.string.where_synced, calendar.host.orEmpty()),
        )
        dialog(
            DialogSpec(str(R.string.confirm_changes)) { dismiss ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { Text(it, fontSize = 15.sp) }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(str(R.string.cancel), dismiss, Modifier.weight(1f))
                        GlassButton(
                            str(if (deleting) R.string.delete else R.string.confirm),
                            {
                                dismiss()
                                onConfirm(s)
                            },
                            Modifier.weight(1f),
                            prominent = !deleting,
                            danger = deleting,
                        )
                    }
                }
            },
        )
    }
    sheet(
        SheetSpec(
            title = str(if (deleting) R.string.series_delete_title else R.string.series_edit_title),
            actions = listOfNotNull(
                SheetAction(str(R.string.scope_this), Icons.Rounded.Event) { summary(SeriesScope.THIS) }.takeIf { !ruleChanged },
                SheetAction(str(R.string.scope_following), Icons.Rounded.EventRepeat) { summary(SeriesScope.FOLLOWING) },
                SheetAction(str(R.string.scope_all), Icons.Rounded.CalendarMonth) { summary(SeriesScope.ALL) },
            ),
        ),
    )
}
