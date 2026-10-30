package dev.liquidcalendar.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import dev.liquidcalendar.widget.AgendaWidgets
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.MainActivity
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.Alarms
import dev.liquidcalendar.data.Agenda
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.data.JsonStore
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.ui.screens.views.timeLabel
import dev.liquidcalendar.ui.screens.views.titleOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Recordatorios con notificaciones locales, con la estrategia de Liquid Clock (AlarmManager y
 * PendingIntent inmutables), pero con una sola alarma programada a la vez: la del próximo
 * recordatorio dentro de [WINDOW]. Al sonar, avisa lo que corresponde y programa la siguiente.
 * Nunca hay red de por medio y los registros no llevan nada del evento.
 */
@SuppressLint("StaticFieldLeak") // Solo guarda el contexto de la aplicación.
object Reminders {
    const val CHANNEL = "reminders"
    private val WINDOW: Duration = Duration.ofDays(7)

    /** Lo más lejos que un recordatorio puede ir antes o después de su evento con las opciones de la app. */
    private val LEAD: Duration = Duration.ofDays(8)

    private lateinit var app: Context

    /** Hasta qué instante ya se avisó (así reprogramar nunca repite un aviso). */
    private lateinit var checked: JsonStore<Long>
    private val mutex = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        checked = JsonStore(File(app.noBackupFilesDir, "reminders-checked.json"), Long.serializer(), 0L)
        if (checked.value == 0L) checked.update { System.currentTimeMillis() }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                // La privacidad en la pantalla de bloqueo la decide cada notificación (ver notify()).
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()

    fun canExact(): Boolean = app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Recordatorios que suenan en (from, to], de los calendarios visibles. */
    private suspend fun due(from: Instant, to: Instant, zone: ZoneId): List<Pair<Instant, AgendaItem>> =
        Agenda.load(from.minus(LEAD), to.plus(LEAD), zone).flatMap { item ->
            item.occurrence.alarms.map { Alarms.triggerAt(it, item.occurrence.time, zone) to item }
        }.filter { (at, _) -> at > from && at <= to }.sortedBy { it.first }

    /** Programa la alarma del próximo recordatorio (o una revisión al final de la ventana). */
    suspend fun reschedule() = mutex.withLock {
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent()
        if (!Stores.settings.value.remindersEnabled) {
            am.cancel(pi)
            return@withLock
        }
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val from = maxOf(Instant.ofEpochMilli(checked.value), now.minus(Duration.ofMinutes(1)))
        val next = due(from, now.plus(WINDOW), zone).firstOrNull()?.first
        val at = (next ?: now.plus(WINDOW)).toEpochMilli()
        if (next != null && Stores.settings.value.exactReminders && canExact()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Sin alarmas exactas: Android puede demorarlo unos minutos para ahorrar batería.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Sonó la alarma: avisar lo que venció desde la última vez y programar la siguiente. */
    suspend fun onAlarm() {
        mutex.withLock {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val from = Instant.ofEpochMilli(checked.value)
            if (Stores.settings.value.remindersEnabled && canNotify()) {
                // Lo vencido hace más de un día (celu apagado mucho tiempo) ya no se avisa.
                due(maxOf(from, now.minus(Duration.ofDays(1))), now.plusSeconds(30), zone).forEach { (at, item) -> notify(at, item, zone) }
            }
            checked.update { now.plusSeconds(30).toEpochMilli() }
        }
        reschedule()
    }

    @SuppressLint("MissingPermission") // Se comprueba en canNotify().
    private fun notify(at: Instant, item: AgendaItem, zone: ZoneId) {
        val title = titleOf(item)
        val text = listOfNotNull(timeLabel(item, null, zone), item.occurrence.location).joinToString(" · ")
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val showOnLockScreen = Stores.settings.value.remindersOnLockScreen
        // Versión pública: lo único que se ve con el celu bloqueado si el detalle está oculto.
        val public = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.reminder_hidden))
            .build()
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(item.calendar.color)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(at.toEpochMilli())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (showOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(app).notify((item.occurrence.key + at.toEpochMilli()).hashCode(), n)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** La alarma del próximo recordatorio. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CalendarApp.scope.launch {
            try {
                Reminders.onAlarm()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reprograma tras reiniciar, actualizar la app o cambiar la hora o la zona del sistema. */
class SystemChangeReceiver : BroadcastReceiver() {
    private val accepted = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        // Solo los mensajes del sistema que interesan; cualquier otro se ignora.
        if (intent.action !in accepted) return
        val pending = goAsync()
        CalendarApp.scope.launch {
            try {
                Reminders.reschedule()
                // Otra hora o zona: el widget recalcula qué es "hoy".
                AgendaWidgets.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
