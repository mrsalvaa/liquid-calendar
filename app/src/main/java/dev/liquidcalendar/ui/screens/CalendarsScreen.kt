package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.background
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
import dev.liquidcalendar.ui.glass.ACCENT_HUES
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.GlassSwitch
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.LocalPrompt
import dev.liquidcalendar.ui.glass.PromptSpec
import dev.liquidcalendar.ui.glass.SectionLabel
import dev.liquidcalendar.ui.glass.SettingRow
import dev.liquidcalendar.ui.glass.muted
import dev.liquidcalendar.ui.screens.views.OriginIcon
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CalendarsScreen(padding: PaddingValues, onBack: () -> Unit, onAccount: () -> Unit, onChanges: () -> Unit) {
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    val prompt = LocalPrompt.current
    val version by Repo.version.collectAsStateWithLifecycle()
    val data by produceState(Pair(emptyList<Account>(), emptyList<CalendarRow>()), version) {
        value = withContext(Dispatchers.IO) { Repo.accounts() to Repo.calendars() }
    }
    val pendingCount by produceState(0, version) { value = withContext(Dispatchers.IO) { Repo.changes().size } }
    val (accounts, calendars) = data
    val caldav = accounts.firstOrNull { !it.isLocal }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

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

        SectionLabel(str(R.string.on_this_phone))
        GlassCard {
            calendars.filter { it.accountId == accounts.firstOrNull { a -> a.isLocal }?.id }.forEach { CalendarItem(it, local = true, host = null) }
            Text(str(R.string.local_only_hint), Modifier.padding(top = 4.dp), fontSize = 12.sp, color = p.contentSecondary)
        }
        GlassButton(
            str(R.string.new_local_calendar),
            {
                prompt(
                    PromptSpec(str(R.string.new_local_calendar), str(R.string.create), str(R.string.calendar_name)) { name ->
                        if (name.isNotBlank()) {
                            // Cada calendario nuevo con el siguiente color de la paleta.
                            val hue = ACCENT_HUES[(calendars.size * 3) % ACCENT_HUES.size]
                            val color = muted(hue.tones[1]).toArgb()
                            scope.launch(Dispatchers.IO) { Repo.addLocalCalendar(name.trim(), color) }
                        }
                    },
                )
            },
            Modifier.fillMaxWidth().padding(top = 10.dp),
            icon = Icons.Rounded.Add,
        )

        if (caldav != null) {
            val host = caldav.serverUrl?.let { (ServerUrl.parse(it, allowHttp = true) as? ServerUrl.Result.Ok)?.url?.host }.orEmpty()
            SectionLabel(str(R.string.account_on_server, caldav.username.orEmpty(), host))
            GlassCard {
                AccountStatus(caldav, syncMessage, onAccount)
                if (pendingCount > 0) SettingRow(str(R.string.pending_changes), subtitle = plural(R.plurals.pending_count, pendingCount), onClick = onChanges)
                calendars.filter { it.accountId == caldav.id }.forEach { CalendarItem(it, local = false, host = host) }
            }
        }
    }
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
private fun CalendarItem(c: CalendarRow, local: Boolean, host: String?) {
    val scope = rememberCoroutineScope()
    SettingRow(
        c.name,
        // Siempre se ve si el calendario vive solo en el celu o se sincroniza con un servidor.
        subtitle = buildString {
            append(if (local) str(R.string.local_badge) else str(R.string.synced_badge, host.orEmpty()))
            append(" · ").append(plural(R.plurals.events_count, c.events))
            if (c.readOnly) append(" · ").append(str(R.string.read_only))
        },
        leading = {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color)))
            Spacer(Modifier.width(10.dp))
            OriginIcon(local, size = 16)
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
