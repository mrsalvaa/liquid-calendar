package dev.liquidcalendar.data

import dev.liquidcalendar.ui.glass.GlassStyle
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

@Serializable
data class AppSettings(
    /** 0 = según el sistema, 1 = claro, 2 = oscuro. */
    val themeMode: Int = 0,
    /** 0 = colores en movimiento (COLOR_SETS), 1 = color de énfasis, 2 = liso con acento azul. */
    val backgroundMode: Int = 2,
    /** Índice en COLOR_SETS. */
    val colorSet: Int = 0,
    /** Color de énfasis en ARGB, para backgroundMode = 1. */
    val accentColor: Long = 0xFF0EA5E9,
    val animatedBackground: Boolean = true,
    /** Índice del icono de la app (ver AppIcons). */
    val appIcon: Int = 0,
    /** Primer día de la semana: 0 = según el idioma del teléfono, 1 (lunes) a 7 (domingo). */
    val firstDayOfWeek: Int = 7,
    /** Sincronización en segundo plano, en minutos (0 = solo a mano). Mínimo 30. */
    val syncIntervalMinutes: Int = 60,
    /** Altura de una hora en las vistas de semana y día (dp); se cambia pellizcando. */
    val hourHeight: Int = 56,
    /** Recordatorios con notificaciones locales. */
    val remindersEnabled: Boolean = true,
    /** Usar alarmas exactas (solo si Android lo permite: el usuario lo concede en los ajustes del sistema). */
    val exactReminders: Boolean = false,
    /** Mostrar el título y la hora en la pantalla de bloqueo. Por defecto, no. */
    val remindersOnLockScreen: Boolean = false,
) {
    val glassStyle: GlassStyle get() = GlassStyle(themeMode, backgroundMode, colorSet, accentColor)

    fun weekStart(locale: Locale = Locale.getDefault()): DayOfWeek =
        if (firstDayOfWeek in 1..7) DayOfWeek.of(firstDayOfWeek) else WeekFields.of(locale).firstDayOfWeek
}
