package dev.liquidcalendar.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.MainActivity
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.When
import dev.liquidcalendar.data.Agenda
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.ui.glass.GlassPalette
import dev.liquidcalendar.ui.glass.glassPalette
import dev.liquidcalendar.ui.screens.views.timeLabel
import dev.liquidcalendar.ui.screens.views.titleOf
import dev.liquidcalendar.util.dayName
import dev.liquidcalendar.util.longDate
import dev.liquidcalendar.util.shortDate
import dev.liquidcalendar.util.str
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Widget de agenda: los eventos de hoy y de los 13 días siguientes, agrupados por día. */
class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CalendarApp.scope.launch {
            try {
                AgendaWidgets.render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        // Ya no queda ningún widget: no hace falta seguir actualizando.
        context.getSystemService(AlarmManager::class.java).cancel(AgendaWidgets.updateIntent(context))
    }
}

object AgendaWidgets {
    /** Días que muestra el widget, contando hoy. */
    const val DAYS = 14

    /** Id de la fila "Ahora" (los días usan ids negativos a partir de -1). */
    private const val NOW_ROW = Long.MIN_VALUE

    /** Tope de filas (encabezados de día incluidos), para no armar listas enormes. */
    private const val MAX_ROWS = 200

    /** Redibuja todos los widgets (si hay alguno): tras sincronizar, editar o cambiar ajustes. */
    suspend fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
        if (ids.isNotEmpty()) render(context, manager, ids)
    }

    suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val items = Agenda.load(today.atStartOfDay(zone).toInstant(), today.plusDays(DAYS.toLong()).atStartOfDay(zone).toInstant(), zone)
            // Lo que ya terminó no se muestra.
            .filterNot { it.isOver(now) }
        // Lo que está en curso va primero, en "Ahora", y no se repite en su día.
        val ongoing = items.filter { it.isOngoing(now) }
        val groups = (items - ongoing.toSet()).groupBy { maxOf(it.days(zone).start, today) }.toSortedMap()

        val settings = Stores.settings.value
        val light = glassPalette(settings.glassStyle, dark = false)
        val dark = glassPalette(settings.glassStyle, dark = true)
        // Según el sistema: Android elige el color claro u oscuro al dibujar. Forzado: el mismo para los dos.
        val colors = when (settings.themeMode) {
            1 -> Colors(light, light)
            2 -> Colors(dark, dark)
            else -> Colors(light, dark)
        }

        val list = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
        var rows = 0
        if (ongoing.isNotEmpty()) {
            list.addItem(NOW_ROW, labelRow(context, str(R.string.now), accent = true, colors))
            rows++
            for (item in ongoing) {
                if (rows >= MAX_ROWS) break
                list.addItem(itemId(item), eventRow(context, item, today, zone, colors))
                rows++
            }
        }
        for ((day, dayItems) in groups) {
            if (rows >= MAX_ROWS) break
            list.addItem(-day.toEpochDay() - 1, dayRow(context, day, today, colors))
            rows++
            for (item in dayItems) {
                if (rows >= MAX_ROWS) break
                list.addItem(itemId(item), eventRow(context, item, day, zone, colors))
                rows++
            }
        }

        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        colors.tint(views, R.id.widget_surface) { it.base.copy(alpha = if (it.dark) 0.9f else 0.72f) }
        colors.tint(views, R.id.widget_rim) { Color.White.copy(alpha = if (it.dark) 0.16f else 0.85f) }
        colors.tint(views, R.id.widget_glow) { it.accent.copy(alpha = if (it.dark) 0.35f else 0.22f) }
        colors.tint(views, R.id.widget_add_bg) { it.accent }
        colors.tint(views, R.id.widget_add_icon) { it.onAccent }
        colors.text(views, R.id.widget_title) { it.content }
        colors.text(views, R.id.widget_subtitle) { it.contentSecondary }
        colors.text(views, R.id.widget_empty) { it.contentSecondary }
        views.setTextViewText(R.id.widget_title, longDate(today))
        views.setRemoteAdapter(R.id.widget_list, list.build())
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        // Tocar el encabezado abre la app; "+" crea un evento hoy; un evento abre su detalle.
        views.setOnClickPendingIntent(R.id.widget_header, activity(context, 1, MainActivity.ACTION_OPEN, mutable = false))
        views.setOnClickPendingIntent(R.id.widget_add, activity(context, 2, MainActivity.ACTION_NEW_EVENT, mutable = false))
        views.setPendingIntentTemplate(R.id.widget_list, activity(context, 3, MainActivity.ACTION_OPEN_EVENT, mutable = true))
        manager.updateAppWidget(ids, views)
        scheduleNext(context, items, now, today, zone)
    }

    /**
     * Vuelve a dibujar cuando empieza o termina el próximo evento con hora (para entrar o salir
     * de "Ahora") o a medianoche, lo que llegue antes. La alarma no despierta al celu: si está
     * dormido, se actualiza al encenderse, y no hace falta ningún permiso.
     */
    private fun scheduleNext(context: Context, items: List<AgendaItem>, now: Instant, today: LocalDate, zone: ZoneId) {
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        val next = items.asSequence()
            .mapNotNull { it.occurrence.time as? When.Timed }
            .flatMap { sequenceOf(it.start, it.end) }
            .filter { it > now }
            .plus(midnight)
            .min()
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, next.toEpochMilli(), updateIntent(context))
    }

    /** Pide al propio widget que se actualice (explícito: solo lo recibe esta app). */
    fun updateIntent(context: Context): PendingIntent {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AgendaWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dayRow(context: Context, day: LocalDate, today: LocalDate, colors: Colors): RemoteViews {
        val label = when (day) {
            today -> str(R.string.today)
            today.plusDays(1) -> str(R.string.widget_tomorrow)
            else -> "${dayName(day.dayOfWeek)} ${shortDate(day)}"
        }
        return labelRow(context, label, accent = day == today, colors)
    }

    private fun labelRow(context: Context, label: String, accent: Boolean, colors: Colors): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_agenda_day).apply {
            setTextViewText(R.id.widget_day, label)
            colors.text(this, R.id.widget_day) { if (accent) it.accentFg else it.contentSecondary }
        }

    private fun eventRow(context: Context, item: AgendaItem, day: LocalDate, zone: ZoneId, colors: Colors): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_agenda_event).apply {
            val time = timeLabel(item, day, zone)
            val location = item.occurrence.location?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            setTextViewText(R.id.widget_event_title, titleOf(item))
            setTextViewText(R.id.widget_event_time, if (location != null) "$time · $location" else time)
            colors.text(this, R.id.widget_event_title) { it.content }
            colors.text(this, R.id.widget_event_time) { it.contentSecondary }
            colors.tint(this, R.id.widget_event_bg) { Color.White.copy(alpha = if (it.dark) 0.08f else 0.92f) }
            setColorStateList(R.id.widget_event_bar, "setImageTintList", ColorStateList.valueOf(item.calendar.color))
            setOnClickFillInIntent(
                R.id.widget_event,
                Intent()
                    .putExtra(MainActivity.EXTRA_CALENDAR, item.calendar.id)
                    .putExtra(MainActivity.EXTRA_UID, item.occurrence.uid)
                    .putExtra(MainActivity.EXTRA_KEY, item.occurrence.key)
                    .putExtra(MainActivity.EXTRA_DAY, day.toEpochDay()),
            )
        }

    private fun itemId(item: AgendaItem): Long =
        (item.calendar.id * 31 + item.occurrence.uid.hashCode()) * 31 + item.occurrence.key.hashCode()

    /**
     * La plantilla de los eventos tiene que ser mutable para recibir los datos de cada fila; es
     * explícita (solo abre esta app), así que nadie más puede redirigirla.
     */
    private fun activity(context: Context, code: Int, action: String, mutable: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )

    /** Colores para tema claro y oscuro: Android elige al dibujar según el modo del sistema. */
    private class Colors(val light: GlassPalette, val dark: GlassPalette) {
        fun text(views: RemoteViews, id: Int, pick: (GlassPalette) -> Color) =
            views.setColorInt(id, "setTextColor", pick(light).toArgb(), pick(dark).toArgb())

        fun tint(views: RemoteViews, id: Int, pick: (GlassPalette) -> Color) =
            views.setColorStateList(id, "setImageTintList", ColorStateList.valueOf(pick(light).toArgb()), ColorStateList.valueOf(pick(dark).toArgb()))
    }
}
