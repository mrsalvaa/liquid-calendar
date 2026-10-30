package dev.liquidcalendar.sync

import dev.liquidcalendar.data.CalendarInfo
import dev.liquidcalendar.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Crear, renombrar, recolorear y borrar calendarios. Los locales nunca tocan el servidor; los
 * sincronizados se cambian primero en el servidor y, solo si responde bien, en el dispositivo.
 * El calendario se identifica siempre por su URL (href), así que cambiarle el nombre o el color
 * no lo desparea ni lo duplica.
 */
object CalendarOps {
    suspend fun createLocal(name: String, color: Int): Long = withContext(Dispatchers.IO) { Repo.addLocalCalendar(name, color) }

    suspend fun createRemote(name: String, color: Int, withTasks: Boolean): SyncEngine.OpResult<Long> =
        SyncEngine.serverOp { caldav, home, accountId ->
            val components = if (withTasks) setOf("VEVENT", "VTODO") else setOf("VEVENT")
            val url = caldav.mkcalendar(home, UUID.randomUUID().toString(), name, color, components)
            Repo.addRemoteCalendar(accountId, url, name, color)
        }

    suspend fun update(calendar: CalendarInfo, name: String, color: Int): SyncEngine.OpResult<Unit> {
        if (calendar.local || calendar.subscription) {
            withContext(Dispatchers.IO) { Repo.updateCalendar(calendar.id, name, color) }
            return SyncEngine.OpResult.Ok(Unit)
        }
        val url = withContext(Dispatchers.IO) { Repo.calendarUrl(calendar.id) } ?: return SyncEngine.OpResult.NoAccount
        return SyncEngine.serverOp { caldav, _, _ ->
            caldav.proppatch(url, name.takeIf { it != calendar.name }, color.takeIf { it != calendar.color })
            Repo.updateCalendar(calendar.id, name, color)
        }
    }

    /** Solo por acción explícita (con el nombre escrito). Nunca por un 404 ni por un error de sincronización. */
    suspend fun delete(calendar: CalendarInfo): SyncEngine.OpResult<Unit> {
        if (calendar.local || calendar.missing) {
            withContext(Dispatchers.IO) { Repo.deleteCalendar(calendar.id) }
            return SyncEngine.OpResult.Ok(Unit)
        }
        if (calendar.subscription) {
            withContext(Dispatchers.IO) { Repo.calendars().firstOrNull { it.id == calendar.id }?.let { Repo.deleteSubscription(it.accountId) } }
            return SyncEngine.OpResult.Ok(Unit)
        }
        val url = withContext(Dispatchers.IO) { Repo.calendarUrl(calendar.id) } ?: return SyncEngine.OpResult.NoAccount
        return SyncEngine.serverOp { caldav, _, _ ->
            caldav.deleteCalendar(url)
            Repo.deleteCalendar(calendar.id)
        }
    }
}
