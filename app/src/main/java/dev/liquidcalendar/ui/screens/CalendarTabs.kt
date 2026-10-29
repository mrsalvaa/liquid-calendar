package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liquidcalendar.R
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.ScreenTitle
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.monthTitle
import dev.liquidcalendar.util.str
import java.time.LocalDate

const val TAB_MONTH = 0
const val TAB_WEEK = 1
const val TAB_DAY = 2
const val TAB_AGENDA = 3
const val TAB_COUNT = 4

/**
 * Pestaña de calendario. Mientras no haya cuenta muestra cómo agregarla; las vistas de mes,
 * semana, día y agenda se dibujan aquí cuando haya eventos.
 */
@Composable
fun CalendarTab(tab: Int, padding: PaddingValues, onSettings: () -> Unit) {
    val today = remember { LocalDate.now() }
    val title = when (tab) {
        TAB_MONTH -> monthTitle(today)
        TAB_WEEK -> str(R.string.tab_week)
        TAB_DAY -> str(R.string.tab_day)
        else -> str(R.string.tab_agenda)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenTitle(
            title,
            subtitle = "${str(R.string.today)}: ${longDate(today)}",
            actions = { GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), onSettings) },
        )
        EmptyState(str(R.string.no_account_title), str(R.string.no_account_subtitle))
        GlassButton(str(R.string.open_settings), onSettings, prominent = true)
    }
}
