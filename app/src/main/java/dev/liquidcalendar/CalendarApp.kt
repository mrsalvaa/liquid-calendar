package dev.liquidcalendar

import android.app.Application
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.sync.SyncEngine
import dev.liquidcalendar.sync.SyncScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import dev.liquidcalendar.notify.Reminders
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.liquidcalendar.util.Strings

class CalendarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.app = this
        Stores.init(this)
        Repo.init(this)
        SyncEngine.init(this)
        SyncScheduler.init(this)
        Reminders.init(this)
        // Cualquier cambio en los eventos (sincronización, edición, visibilidad) reprograma los recordatorios.
        scope.launch {
            Repo.version.debounce(1500).collect { Reminders.reschedule() }
        }
        SyncScheduler.refreshSchedule()
    }

    companion object {
        /** Trabajo que debe terminar aunque se cierre la pantalla que lo pidió (guardar, sincronizar). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
