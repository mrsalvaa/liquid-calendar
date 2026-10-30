package dev.liquidcalendar.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import dev.liquidcalendar.ui.glass.SettingRow
import dev.liquidcalendar.ui.screens.views.OriginIcon
import dev.liquidcalendar.ui.screens.views.originText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.dav.DavException
import dev.liquidcalendar.core.feed.FeedUrl
import dev.liquidcalendar.core.ics.IcsFiles
import dev.liquidcalendar.core.ics.ImportPreview
import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Events
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.sync.CalendarOps
import dev.liquidcalendar.sync.SyncEngine
import dev.liquidcalendar.sync.SyncScheduler
import dev.liquidcalendar.sync.Subscriptions
import dev.liquidcalendar.ui.glass.ACCENT_HUES
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.GlassTextField
import dev.liquidcalendar.ui.glass.LocalDialog
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SectionLabel
import dev.liquidcalendar.ui.glass.muted
import dev.liquidcalendar.ui.screens.views.CalendarBadge
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Colores para calendarios: los diez del sistema de diseño, en tono medio y apagado. */
val CALENDAR_COLORS: List<Int> = ACCENT_HUES.map { muted(it.tones[1]).toArgb() }

@Composable
fun ColorPicker(selected: Int, onPick: (Int) -> Unit) {
    val p = LocalGlass.current
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CALENDAR_COLORS.forEachIndexed { i, c ->
            Box(
                Modifier
                    .size(40.dp)
                    .border(2.dp, if (c == selected) p.content else Color.Transparent, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .selectable(c == selected, role = Role.RadioButton) { onPick(c) }
                    .semantics { contentDescription = str(ACCENT_HUES[i].name) },
            )
        }
    }
}

/**
 * Exportar: primero el aviso de que el archivo no va cifrado y después el selector del sistema
 * (sin permisos de almacenamiento). [content] se arma recién al confirmar.
 */
@Composable
fun rememberExport(): (fileName: String, content: suspend () -> String) -> Unit {
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    var pending by remember { mutableStateOf<(suspend () -> String)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val make = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        CalendarApp.scope.launch(Dispatchers.IO) {
            val text = make()
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            withContext(Dispatchers.Main) { toast(str(R.string.exported)) }
        }
    }
    return { name, content ->
        dialog(
            DialogSpec(str(R.string.export_ics)) { dismiss ->
                Text(str(R.string.export_warning), fontSize = 15.sp)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(str(R.string.cancel), dismiss, Modifier.weight(1f))
                    GlassButton(str(R.string.export_continue), {
                        dismiss()
                        pending = content
                        launcher.launch(name)
                    }, Modifier.weight(1f), prominent = true)
                }
            },
        )
    }
}

fun exportCalendar(export: (String, suspend () -> String) -> Unit, calendar: CalendarInfo) =
    export(IcsFiles.fileName(calendar.name, LocalDate.now())) { IcsFiles.export(Repo.eventsOf(calendar.id)) }

/** Qué se edita: un calendario nuevo (local o en el servidor) o uno existente. */
sealed interface CalendarTarget {
    data object NewLocal : CalendarTarget
    data object NewRemote : CalendarTarget
    data class Existing(val calendarId: Long) : CalendarTarget
}

private fun opMessage(r: SyncEngine.OpResult<*>): String? = when (r) {
    is SyncEngine.OpResult.Ok -> null
    SyncEngine.OpResult.NoAccount -> str(R.string.not_configured)
    SyncEngine.OpResult.NeedsPassword -> str(R.string.needs_password_title)
    is SyncEngine.OpResult.Wait -> str(R.string.err_busy)
    is SyncEngine.OpResult.Failed -> if (r.error is DavException.MkcalendarUnsupported) str(R.string.mkcalendar_unsupported) else errorMessage(r.error)
}

@Composable
fun CalendarEditScreen(target: CalendarTarget, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val scope = rememberCoroutineScope()
    val export = rememberExport()
    val existing by produceState<CalendarInfo?>(null, target) {
        value = (target as? CalendarTarget.Existing)?.let { t -> withContext(Dispatchers.IO) { Repo.calendarInfos()[t.calendarId] } }
    }
    val c = existing
    if (target is CalendarTarget.Existing && c == null) return
    var name by rememberSaveable(c?.id) { mutableStateOf(c?.name.orEmpty()) }
    var color by rememberSaveable(c?.id) { mutableIntStateOf(c?.color ?: CALENDAR_COLORS[6]) }
    var withTasks by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        BackTitle(
            when (target) {
                CalendarTarget.NewLocal -> str(R.string.new_local_calendar)
                CalendarTarget.NewRemote -> str(R.string.new_remote_calendar)
                is CalendarTarget.Existing -> c!!.name
            },
            onBack,
        )
        if (c != null) GlassCard(Modifier.padding(bottom = 12.dp)) { CalendarBadge(c) }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(name, { name = it; message = null }, str(R.string.calendar_name))
                Text(str(R.string.calendar_color), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ColorPicker(color) { color = it }
                if (target == CalendarTarget.NewRemote) {
                    Text(str(R.string.calendar_content), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    ChipRow(listOf(false, true), withTasks, { str(if (it) R.string.kind_events_tasks else R.string.kind_events) }) { withTasks = it }
                }
            }
        }
        if (message != null) Text(message!!, Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, color = p.danger)
        GlassButton(
            str(R.string.save),
            {
                if (name.isBlank()) return@GlassButton
                busy = true
                scope.launch {
                    val r = when (target) {
                        CalendarTarget.NewLocal -> SyncEngine.OpResult.Ok(CalendarOps.createLocal(name.trim(), color))
                        CalendarTarget.NewRemote -> CalendarOps.createRemote(name.trim(), color, withTasks).also { if (it is SyncEngine.OpResult.Ok) SyncScheduler.pushSoon() }
                        is CalendarTarget.Existing -> CalendarOps.update(c!!, name.trim(), color)
                    }
                    busy = false
                    message = opMessage(r)
                    if (message == null) onBack()
                }
            },
            Modifier.fillMaxWidth().padding(top = 16.dp),
            prominent = true,
            enabled = !busy && name.isNotBlank(),
        )
        if (c != null) {
            GlassButton(str(R.string.export_ics), { exportCalendar(export, c) }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = Icons.Rounded.FileDownload)
            GlassButton(
                str(R.string.delete_calendar),
                {
                    scope.launch {
                        val count = withContext(Dispatchers.IO) { Repo.eventCount(c.id) }
                        dialog(DialogSpec(str(R.string.delete_calendar_title, c.name)) { dismiss -> DeleteCalendarContent(c, count, dismiss, { exportCalendar(export, c) }) { onBack() } })
                    }
                },
                Modifier.fillMaxWidth().padding(top = 10.dp),
                icon = Icons.Rounded.DeleteOutline,
                danger = true,
                enabled = !busy,
            )
        }
    }
}

/** Borrado fuerte: cuántos eventos se pierden, que es irreversible, exportar antes y escribir el nombre. */
@Composable
internal fun DeleteCalendarContent(c: CalendarInfo, count: Int, dismiss: () -> Unit, exportFirst: () -> Unit, onDeleted: () -> Unit) {
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(plural(R.plurals.delete_calendar_events, count, count), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (c.local || c.subscription || c.missing) str(R.string.delete_calendar_local) else str(R.string.delete_calendar_server, c.host.orEmpty()),
            fontSize = 15.sp,
            color = p.danger,
        )
        GlassButton(str(R.string.export_first), { dismiss(); exportFirst() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileDownload)
        Text(str(R.string.delete_calendar_type), fontSize = 13.sp, color = p.contentSecondary)
        GlassTextField(typed, { typed = it }, c.name)
        if (error != null) Text(error!!, fontSize = 13.sp, color = p.danger)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(str(R.string.cancel), dismiss, Modifier.weight(1f))
            GlassButton(
                str(R.string.delete),
                {
                    scope.launch {
                        val r = CalendarOps.delete(c)
                        error = opMessage(r)
                        if (error == null) {
                            dismiss()
                            onDeleted()
                        }
                    }
                },
                Modifier.weight(1f),
                danger = true,
                enabled = IcsFiles.deleteConfirmed(typed, c.name),
            )
        }
    }
}

/** Alta o edición de una suscripción (solo en el dispositivo, de solo lectura). */
@Composable
fun SubscriptionScreen(calendarId: Long?, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val scope = rememberCoroutineScope()
    val export = rememberExport()
    val existing by produceState<Pair<CalendarInfo, dev.liquidcalendar.data.Account>?>(null, calendarId) {
        value = calendarId?.let { id ->
            withContext(Dispatchers.IO) {
                val info = Repo.calendarInfos()[id] ?: return@withContext null
                val account = Repo.calendars().firstOrNull { it.id == id }?.let { Repo.account(it.accountId) } ?: return@withContext null
                info to account
            }
        }
    }
    if (calendarId != null && existing == null) return
    val info = existing?.first
    val account = existing?.second
    var url by rememberSaveable { mutableStateOf(account?.serverUrl.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(info?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(info?.color ?: CALENDAR_COLORS[3]) }
    var interval by rememberSaveable { mutableIntStateOf(account?.refreshMinutes ?: Subscriptions.DEFAULT_INTERVAL) }
    var message by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accepted = remember { mutableStateOf(account?.acceptedHosts.orEmpty()) }
    val feed = (Subscriptions.parse(url) as? FeedUrl.Result.Ok)?.url

    fun intervalLabel(m: Int) = when {
        m == 0 -> str(R.string.sync_manual)
        m < 1440 -> str(R.string.sync_every_h, m / 60)
        m < 10080 -> str(R.string.sync_every_day)
        else -> str(R.string.sync_every_week)
    }

    fun askRedirect(to: FeedUrl, onFollow: () -> Unit) = dialog(
        DialogSpec(str(R.string.feed_redirect_title)) { dismiss ->
            Text(str(R.string.feed_redirect_text, to.authority), fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(str(R.string.cancel), dismiss, Modifier.weight(1f))
                GlassButton(str(R.string.follow), { dismiss(); onFollow() }, Modifier.weight(1f), prominent = true)
            }
        },
    )

    fun save() {
        val f = feed ?: run {
            message = str(R.string.feed_invalid)
            return
        }
        busy = true
        scope.launch {
            if (account != null) {
                withContext(Dispatchers.IO) {
                    Repo.updateCalendar(info!!.id, name.ifBlank { info.name }, color)
                    Repo.updateSubscription(account.id, interval)
                }
                busy = false
                onBack()
                return@launch
            }
            // Una sola descarga: si falla o redirige a otro servidor, no se guarda nada.
            when (val r = Subscriptions.subscribe(f, name.ifBlank { f.host }, color, interval, accepted.value)) {
                is Subscriptions.Result.Redirect -> {
                    busy = false
                    askRedirect(r.to) {
                        accepted.value = accepted.value + r.to.authority
                        save()
                    }
                }
                is Subscriptions.Result.Ok, Subscriptions.Result.Unchanged -> {
                    SyncScheduler.refreshSchedule()
                    busy = false
                    onBack()
                }
                is Subscriptions.Result.Failed -> {
                    busy = false
                    message = r.error?.let { errorMessage(it) } ?: str(R.string.feed_not_ics)
                }
                is Subscriptions.Result.Wait -> {
                    busy = false
                    message = str(R.string.err_busy)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        BackTitle(info?.name ?: str(R.string.add_subscription), onBack)
        if (info != null) GlassCard(Modifier.padding(bottom = 12.dp)) { CalendarBadge(info) }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(url, { url = it; message = null }, str(R.string.subscription_url), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), enabled = account == null)
                GlassTextField(name, { name = it }, str(R.string.calendar_name))
                Text(str(R.string.calendar_color), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ColorPicker(color) { color = it }
                Text(str(R.string.refresh_every), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ChipRow(Subscriptions.INTERVALS, interval, ::intervalLabel) { interval = it }
            }
        }
        // Siempre a la vista: el servidor del feed ve la IP del dispositivo.
        GlassCard(Modifier.padding(top = 12.dp)) {
            Text(feed?.let { str(R.string.subscription_ip_notice, it.host) } ?: str(R.string.subscription_ip_notice_generic), fontSize = 13.sp, color = p.contentSecondary)
        }
        if (message != null) Text(message!!, Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, color = p.danger)
        if (notice != null) Text(notice!!, Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, color = p.contentSecondary)
        GlassButton(str(R.string.save), ::save, Modifier.fillMaxWidth().padding(top = 16.dp), prominent = true, enabled = !busy && feed != null)
        if (account != null && info != null) {
            fun refreshNow() {
                busy = true
                message = null
                notice = null
                scope.launch {
                    // La cuenta se relee: puede tener un host recién aceptado.
                    val current = withContext(Dispatchers.IO) { Repo.account(account.id) } ?: return@launch
                    val r = Subscriptions.refresh(current, force = true)
                    busy = false
                    when (r) {
                        is Subscriptions.Result.Ok -> notice = plural(R.plurals.feed_events, r.events, r.events)
                        Subscriptions.Result.Unchanged -> notice = str(R.string.feed_unchanged)
                        is Subscriptions.Result.Redirect -> askRedirect(r.to) {
                            scope.launch {
                                withContext(Dispatchers.IO) { Repo.acceptHost(account.id, r.to.authority) }
                                refreshNow()
                            }
                        }
                        is Subscriptions.Result.Wait -> message = str(R.string.err_busy)
                        is Subscriptions.Result.Failed -> message = r.error?.let { errorMessage(it) } ?: str(R.string.feed_not_ics)
                    }
                }
            }
            GlassButton(str(R.string.refresh_now), ::refreshNow, Modifier.fillMaxWidth().padding(top = 10.dp), icon = Icons.Rounded.Sync, enabled = !busy)
            GlassButton(str(R.string.export_ics), { exportCalendar(export, info) }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = Icons.Rounded.FileDownload)
            GlassButton(str(R.string.delete_calendar), {
                scope.launch {
                    val count = withContext(Dispatchers.IO) { Repo.eventCount(info.id) }
                    dialog(DialogSpec(str(R.string.delete_calendar_title, info.name)) { dismiss -> DeleteCalendarContent(info, count, dismiss, { exportCalendar(export, info) }) { onBack() } })
                }
            }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = Icons.Rounded.DeleteOutline, danger = true)
        }
    }
}

/** Vista previa de una importación: cuántos eventos, a qué calendario, y cuántos ya estaban. */
@Composable
fun ImportScreen(preview: ImportPreview, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val toast = LocalToast.current
    val calendars by produceState<List<CalendarInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) { Repo.calendarInfos().values.filter { !it.readOnly && !it.subscription && !it.missing }.sortedWith(compareBy({ !it.local }, { it.name })) }
    }
    var target by remember { mutableStateOf<CalendarInfo?>(null) }
    val chosen = target ?: calendars.firstOrNull()
    val duplicates by produceState(0, chosen) {
        value = chosen?.let { c -> withContext(Dispatchers.IO) { preview.events.count { Repo.event(c.id, it.uid) != null } } } ?: 0
    }
    LaunchedEffect(calendars) { if (target == null) target = calendars.firstOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        BackTitle(str(R.string.import_ics), onBack)
        GlassCard {
            Text(plural(R.plurals.import_events, preview.events.size, preview.events.size), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val series = preview.events.count { it.series }
            if (series > 0) Text(plural(R.plurals.import_series, series, series), fontSize = 14.sp, color = p.contentSecondary)
            if (preview.skipped > 0) Text(plural(R.plurals.import_skipped, preview.skipped, preview.skipped), fontSize = 14.sp, color = p.contentSecondary)
            if (duplicates > 0) Text(plural(R.plurals.import_duplicates, duplicates, duplicates), fontSize = 14.sp, color = p.contentSecondary)
        }
        SectionLabel(str(R.string.import_into))
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                calendars.forEach { c ->
                    // Cada opción dice si es local o sincronizado (lo importado se sube en ese caso).
                    SettingRow(
                        c.name,
                        Modifier.selectable(c.id == chosen?.id, role = Role.RadioButton) { target = c },
                        subtitle = originText(c),
                        leading = {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color)))
                            Spacer(Modifier.width(10.dp))
                            OriginIcon(c, size = 16)
                            Spacer(Modifier.width(10.dp))
                        },
                    ) {
                        Icon(
                            if (c.id == chosen?.id) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                            null,
                            tint = if (c.id == chosen?.id) p.accent else p.contentSecondary,
                        )
                    }
                }
            }
        }
        GlassButton(
            str(R.string.import_button),
            {
                val c = chosen ?: return@GlassButton
                CalendarApp.scope.launch {
                    val n = Events.importInto(c, preview.events)
                    withContext(Dispatchers.Main) { toast(plural(R.plurals.imported, n, n)) }
                }
                onBack()
            },
            Modifier.fillMaxWidth().padding(top = 16.dp),
            prominent = true,
            enabled = chosen != null && preview.events.size > duplicates,
        )
    }
}
