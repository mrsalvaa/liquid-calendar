package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import dev.liquidcalendar.R
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.ui.screens.views.AgendaView
import dev.liquidcalendar.ui.screens.views.DeviceClock
import dev.liquidcalendar.ui.screens.views.MonthView
import dev.liquidcalendar.ui.screens.views.PeriodHeader
import dev.liquidcalendar.ui.screens.views.TimelineView
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.monthTitle
import dev.liquidcalendar.util.str
import dev.liquidcalendar.util.weekTitle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

const val TAB_MONTH = 0
const val TAB_WEEK = 1
const val TAB_DAY = 2
const val TAB_AGENDA = 3
const val TAB_COUNT = 4

/** Lo que necesita cada pestaña; la fecha elegida es la misma para todas. */
class TabContext(
    val selected: LocalDate,
    val onSelect: (LocalDate) -> Unit,
    val clock: DeviceClock,
    val weekStart: DayOfWeek,
    val padding: PaddingValues,
    val onEvent: (AgendaItem) -> Unit,
    /** Abrir la vista Día en esa fecha. */
    val onOpenDay: (LocalDate) -> Unit,
    val onSettings: () -> Unit,
    val onCalendars: () -> Unit,
)

@Composable
fun CalendarTab(tab: Int, ctx: TabContext) {
    val dir = LocalLayoutDirection.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = ctx.padding.calculateTopPadding(), start = ctx.padding.calculateStartPadding(dir), end = ctx.padding.calculateEndPadding(dir))
            .padding(horizontal = 12.dp),
    ) {
        val bottom = Modifier.padding(bottom = ctx.padding.calculateBottomPadding())
        when (tab) {
            TAB_MONTH -> {
                val month = YearMonth.from(ctx.selected)
                PeriodHeader(
                    monthTitle(month.atDay(1)), null,
                    str(R.string.prev_month), str(R.string.next_month),
                    showToday = YearMonth.from(ctx.clock.today) != month,
                    onPrev = { ctx.onSelect(month.minusMonths(1).atDay(1)) },
                    onNext = { ctx.onSelect(month.plusMonths(1).atDay(1)) },
                    onToday = { ctx.onSelect(ctx.clock.today) },
                    onCalendars = ctx.onCalendars, onSettings = ctx.onSettings,
                )
                MonthView(month, ctx.weekStart, ctx.clock, { ctx.onSelect(it.atDay(1)) }, ctx.onOpenDay, bottom.weight(1f))
            }
            TAB_WEEK -> {
                val start = ctx.selected.with(TemporalAdjusters.previousOrSame(ctx.weekStart))
                val days = (0L until 7L).map { start.plusDays(it) }
                PeriodHeader(
                    weekTitle(start), null,
                    str(R.string.prev_week), str(R.string.next_week),
                    showToday = ctx.clock.today !in days,
                    onPrev = { ctx.onSelect(ctx.selected.minusWeeks(1)) },
                    onNext = { ctx.onSelect(ctx.selected.plusWeeks(1)) },
                    onToday = { ctx.onSelect(ctx.clock.today) },
                    onCalendars = ctx.onCalendars, onSettings = ctx.onSettings,
                )
                TimelineView(days, ctx.clock, ctx.onEvent, ctx.onOpenDay, bottom.weight(1f))
            }
            TAB_DAY -> {
                PeriodHeader(
                    longDate(ctx.selected), if (ctx.selected == ctx.clock.today) str(R.string.today) else null,
                    str(R.string.prev_day), str(R.string.next_day),
                    showToday = ctx.selected != ctx.clock.today,
                    onPrev = { ctx.onSelect(ctx.selected.minusDays(1)) },
                    onNext = { ctx.onSelect(ctx.selected.plusDays(1)) },
                    onToday = { ctx.onSelect(ctx.clock.today) },
                    onCalendars = ctx.onCalendars, onSettings = ctx.onSettings,
                )
                TimelineView(listOf(ctx.selected), ctx.clock, ctx.onEvent, null, bottom.weight(1f))
            }
            else -> {
                PeriodHeader(
                    str(R.string.tab_agenda), longDate(ctx.selected),
                    str(R.string.prev_week), str(R.string.next_week),
                    showToday = ctx.selected != ctx.clock.today,
                    onPrev = { ctx.onSelect(ctx.selected.minusWeeks(1)) },
                    onNext = { ctx.onSelect(ctx.selected.plusWeeks(1)) },
                    onToday = { ctx.onSelect(ctx.clock.today) },
                    onCalendars = ctx.onCalendars, onSettings = ctx.onSettings,
                )
                AgendaView(ctx.selected, ctx.clock, ctx.onEvent, PaddingValues(bottom = ctx.padding.calculateBottomPadding()), Modifier.weight(1f))
            }
        }
    }
}
