package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.ui.platform.LocalContext
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.IcsFiles
import dev.liquidcalendar.core.ics.ImportPreview
import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Db
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.LocalDialog
import dev.liquidcalendar.ui.screens.views.originText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ServerUrl
import dev.liquidcalendar.data.Account
import dev.liquidcalendar.data.AccountState
import dev.liquidcalendar.data.CalendarRow
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.sync.SyncEngine
import dev.liquidcalendar.sync.SyncResult
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.GlassSwitch
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SectionLabel
import dev.liquidcalendar.ui.glass.SettingRow
import dev.liquidcalendar.ui.screens.views.OriginIcon
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lo que se puede abrir desde la lista de calendarios. */
sealed interface CalendarsAction {
    data class Edit(val target: CalendarTarget) : CalendarsAction
    data class Subscription(val calendarId: Long?) : CalendarsAction
    data class Import(val preview: ImportPreview) : CalendarsAction
}

@Composable
fun CalendarsScreen(padding: PaddingValues, onBack: () -> Unit, onAccount: () -> Unit, onChanges: () -> Unit, onAction: (CalendarsAction) -> Unit) {
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val export = rememberExport()
    val version by Repo.version.collectAsStateWithLifecycle()
    val data by produceState(Triple(emptyList<Account>(), emptyList<CalendarRow>(), emptyMap<Long, CalendarInfo>()), version) {
        value = withContext(Dispatchers.IO) { Triple(Repo.accounts(), Repo.calendars(), Repo.calendarInfos()) }
    }
    val pendingCount by produceState(0, version) { value = withContext(Dispatchers.IO) { Repo.changes().size } }
    val (accounts, calendars, infos) = data
    val caldav = accounts.firstOrNull { it.kind == Db.KIND_CALDAV }
    val subscriptions = accounts.filter { it.isSubscription }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    // Importar: el selector del sistema da acceso solo a ese archivo (sin permisos de almacenamiento).
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, IcsFiles.MAX_IMPORT_CHARS.toLong()) }
                    bytes?.let { b -> withContext(Dispatchers.Default) { IcsFiles.preview(b.toString(Charsets.UTF_8)) } }
                } catch (e: Exception) {
                    null
                } catch (e: StackOverflowError) {
                    null
                }
            }
            if (preview == null || preview.events.isEmpty()) toast(str(R.string.import_error)) else onAction(CalendarsAction.Import(preview))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        BackTitle(str(R.string.calendars), onBack, actions = {
            if (caldav != null) {
                GlassIconButton(
                    Icons.Rounded.Sync,
                    str(R.string.sync_now),
                    {
                        syncing = true
                        syncMessage = str(R.string.syncing)
                        scope.launch {
                            syncMessage = when (val r = SyncEngine.sync()) {
                                SyncResult.Ok -> str(R.string.sync_done)
                                SyncResult.NoAccount -> null
                                SyncResult.NeedsPassword -> str(R.string.needs_password_title)
                                is SyncResult.Wait -> str(R.string.wait_until, timeOf(r.until))
                                is SyncResult.Failed -> errorMessage(r.error)
                            }
                            syncing = false
                        }
                    },
                    enabled = !syncing && caldav.state != AccountState.NEEDS_PASSWORD,
                )
            }
        })

        // Calendarios que desaparecieron del servidor: no se borra nada sin preguntar.
        calendars.filter { it.missing }.forEach { c ->
            val info = infos[c.id] ?: return@forEach
            GlassCard(Modifier.padding(top = 8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(R.string.missing_title, c.name), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.danger)
                    Text(plural(R.plurals.missing_text, c.events), fontSize = 14.sp, color = p.contentSecondary)
                    GlassButton(str(R.string.keep_local), { scope.launch(Dispatchers.IO) { Repo.keepAsLocal(c.id) } }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PhoneAndroid, prominent = true)
                    GlassButton(str(R.string.export_ics), { exportCalendar(export, info) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileDownload)
                    GlassButton(str(R.string.discard_calendar), {
                        dialog(DialogSpec(str(R.string.delete_calendar_title, c.name)) { dismiss -> DeleteCalendarContent(info, c.events, dismiss, { exportCalendar(export, info) }) {} })
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteOutline, danger = true)
                }
            }
        }

        SectionLabel(str(R.string.on_this_phone))
        GlassCard {
            calendars.filter { it.accountId == accounts.firstOrNull { a -> a.isLocal }?.id }.forEach { c ->
                CalendarItem(c, infos[c.id]) { onAction(CalendarsAction.Edit(CalendarTarget.Existing(c.id))) }
            }
            Text(str(R.string.local_only_hint), Modifier.padding(top = 4.dp), fontSize = 12.sp, color = p.contentSecondary)
        }
        GlassButton(
            str(R.string.new_local_calendar),
            { onAction(CalendarsAction.Edit(CalendarTarget.NewLocal)) },
            Modifier.fillMaxWidth().padding(top = 10.dp),
            icon = Icons.Rounded.Add,
        )

        if (caldav != null) {
            val host = caldav.serverUrl?.let { (ServerUrl.parse(it, allowHttp = true) as? ServerUrl.Result.Ok)?.url?.host }.orEmpty()
            SectionLabel(str(R.string.account_on_server, caldav.username.orEmpty(), host))
            GlassCard {
                AccountStatus(caldav, syncMessage, onAccount)
                if (pendingCount > 0) SettingRow(str(R.string.pending_changes), subtitle = plural(R.plurals.pending_count, pendingCount), onClick = onChanges)
                calendars.filter { it.accountId == caldav.id && !it.missing }.forEach { c ->
                    CalendarItem(c, infos[c.id]) { onAction(CalendarsAction.Edit(CalendarTarget.Existing(c.id))) }
                }
            }
            GlassButton(
                str(R.string.new_remote_calendar),
                { onAction(CalendarsAction.Edit(CalendarTarget.NewRemote)) },
                Modifier.fillMaxWidth().padding(top = 10.dp),
                icon = Icons.Rounded.Add,
                enabled = caldav.state != AccountState.NEEDS_PASSWORD,
            )
        }

        SectionLabel(str(R.string.subscriptions))
        GlassCard {
            subscriptions.forEach { a ->
                calendars.filter { it.accountId == a.id }.forEach { c ->
                    CalendarItem(c, infos[c.id]) { onAction(CalendarsAction.Subscription(c.id)) }
                }
            }
            // Siempre a la vista: cada suscripción le revela la IP al servidor del feed.
            Text(str(R.string.subscription_ip_notice_generic), Modifier.padding(top = 4.dp), fontSize = 12.sp, color = p.contentSecondary)
        }
        GlassButton(
            str(R.string.add_subscription),
            { onAction(CalendarsAction.Subscription(null)) },
            Modifier.fillMaxWidth().padding(top = 10.dp),
            icon = Icons.Rounded.RssFeed,
        )
        GlassButton(
            str(R.string.import_ics),
            { importer.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream")) },
            Modifier.fillMaxWidth().padding(top = 10.dp),
            icon = Icons.Rounded.FileUpload,
        )
    }
}

/** Lee como mucho [max] bytes; si el archivo es más grande, falla (nunca se carga entero). */
private fun readLimited(input: java.io.InputStream, max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw IcsException("TooLarge")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun AccountStatus(account: Account, message: String?, onAccount: () -> Unit) {
    val p = LocalGlass.current
    when {
        account.state == AccountState.NEEDS_PASSWORD -> SettingRow(
            str(R.string.needs_password_title),
            subtitle = str(R.string.needs_password_text),
            onClick = onAccount,
        )
        account.retryAt != null && account.retryAt > System.currentTimeMillis() ->
            Text(str(R.string.wait_until, timeOf(Instant.ofEpochMilli(account.retryAt))), fontSize = 13.sp, color = p.contentSecondary)
        account.state == AccountState.ERROR && message == null ->
            Text(errorMessage(account.lastError), fontSize = 13.sp, color = p.danger)
        else -> Text(
            message ?: account.lastSync?.let { str(R.string.last_sync, dateTimeOf(Instant.ofEpochMilli(it))) } ?: str(R.string.never_synced),
            fontSize = 13.sp,
            color = p.contentSecondary,
        )
    }
}

@Composable
private fun CalendarItem(c: CalendarRow, info: CalendarInfo?, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    SettingRow(
        c.name,
        // Siempre se ve si el calendario vive solo en el dispositivo, se sincroniza o es una suscripción.
        subtitle = buildString {
            if (info != null) append(originText(info)).append(" · ")
            append(plural(R.plurals.events_count, c.events))
            if (c.readOnly && info?.subscription != true) append(" · ").append(str(R.string.read_only))
        },
        onClick = onOpen,
        leading = {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color)))
            Spacer(Modifier.width(10.dp))
            if (info != null) OriginIcon(info, size = 16)
            Spacer(Modifier.width(10.dp))
        },
    ) {
        Box(Modifier.semantics { contentDescription = str(R.string.show_calendar, c.name) }) {
            GlassSwitch(c.visible, { v -> scope.launch(Dispatchers.IO) { Repo.setVisible(c.id, v) } })
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private fun timeOf(i: Instant): String = timeFormat.format(LocalDateTime.ofInstant(i, ZoneId.systemDefault()))
private fun dateTimeOf(i: Instant): String = dateTimeFormat.format(LocalDateTime.ofInstant(i, ZoneId.systemDefault()))
