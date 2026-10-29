package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcalendar.BuildConfig
import dev.liquidcalendar.R
import dev.liquidcalendar.data.AppSettings
import dev.liquidcalendar.core.ServerUrl
import dev.liquidcalendar.data.AccountState
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.sync.SyncScheduler
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SectionLabel
import dev.liquidcalendar.ui.glass.SettingRow
import dev.liquidcalendar.util.dayName
import dev.liquidcalendar.util.str
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun SettingsScreen(padding: PaddingValues, onBack: () -> Unit, onAccount: () -> Unit, onCalendars: () -> Unit) {
    val p = LocalGlass.current
    val s by Stores.settings.state.collectAsStateWithLifecycle()
    val version by Repo.version.collectAsStateWithLifecycle()
    val account by produceState(Repo.caldavAccount(), version) { value = withContext(Dispatchers.IO) { Repo.caldavAccount() } }
    val edit: ((AppSettings) -> AppSettings) -> Unit = { Stores.settings.update(it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        BackTitle(str(R.string.settings), onBack)

        SectionLabel(str(R.string.account))
        GlassCard {
            val a = account
            SettingRow(
                str(if (a == null) R.string.caldav_add else R.string.caldav_server),
                subtitle = when {
                    a == null -> str(R.string.not_configured)
                    a.state == AccountState.NEEDS_PASSWORD -> str(R.string.needs_password_short)
                    else -> str(R.string.account_on_server, a.username.orEmpty(), a.serverUrl?.let { u -> (ServerUrl.parse(u, allowHttp = true) as? ServerUrl.Result.Ok)?.url?.host }.orEmpty())
                },
                onClick = onAccount,
            )
            SettingRow(str(R.string.calendars), subtitle = str(R.string.local_only_hint), onClick = onCalendars)
        }

        SectionLabel(str(R.string.appearance))
        AppearanceCard(s, edit)

        SectionLabel(str(R.string.calendar_section))
        GlassCard {
            Text(str(R.string.first_day), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            val localeStart = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            // Domingo es el predeterminado; 0 = según el idioma del teléfono.
            ChipRow(listOf(DayOfWeek.SUNDAY.value, DayOfWeek.MONDAY.value, DayOfWeek.SATURDAY.value, 0), s.firstDayOfWeek, { v ->
                if (v == 0) str(R.string.first_day_auto, dayName(localeStart).lowercase()) else dayName(DayOfWeek.of(v))
            }) { v -> edit { it.copy(firstDayOfWeek = v) } }
        }

        if (account != null) {
            SectionLabel(str(R.string.sync_interval))
            GlassCard {
                ChipRow(SyncScheduler.INTERVALS, s.syncIntervalMinutes, { m ->
                    when {
                        m == 0 -> str(R.string.sync_manual)
                        m < 60 -> str(R.string.sync_every_min, m)
                        else -> str(R.string.sync_every_h, m / 60)
                    }
                }) { m ->
                    edit { it.copy(syncIntervalMinutes = m) }
                    SyncScheduler.schedulePeriodic(m, hasAccount = true)
                }
                Text(str(R.string.sync_interval_hint), fontSize = 12.sp, color = p.contentSecondary)
            }
        }

        SectionLabel(str(R.string.privacy))
        GlassCard {
            Text(str(R.string.privacy_text), fontSize = 14.sp, color = p.contentSecondary)
        }

        SectionLabel(str(R.string.about))
        GlassCard {
            SettingRow("Liquid Calendar", subtitle = str(R.string.version, BuildConfig.VERSION_NAME))
            Text(str(R.string.about_text), fontSize = 13.sp, color = p.contentSecondary)
        }
    }
}
