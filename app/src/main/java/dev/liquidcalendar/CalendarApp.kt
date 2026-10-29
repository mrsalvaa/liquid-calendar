package dev.liquidcalendar

import android.app.Application
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.sync.SyncEngine
import dev.liquidcalendar.sync.SyncScheduler
import kotlinx.coroutines.launch
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
        scope.launch {
            val hasAccount = withContext(Dispatchers.IO) { Repo.caldavAccount() != null }
            SyncScheduler.schedulePeriodic(Stores.settings.value.syncIntervalMinutes, hasAccount)
        }
    }

    companion object {
        /** Trabajo que debe terminar aunque se cierre la pantalla que lo pidió (guardar, sincronizar). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
