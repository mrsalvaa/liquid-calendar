package dev.liquidcalendar.data

import dev.liquidcalendar.core.ics.EventDraft
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.IcsWriter
import dev.liquidcalendar.core.sync.ChangeKind
import dev.liquidcalendar.core.sync.ChangeState
import dev.liquidcalendar.core.sync.PendingChange
import dev.liquidcalendar.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Por qué no se puede editar o borrar un evento (null = se puede). */
enum class EditBlock {
    /** Serie recurrente: solo lectura hasta que se pueda editar una ocurrencia sin tocar las demás. */
    SERIES,
    READ_ONLY,

    /** Tiene un cambio en conflicto o fallido: primero hay que resolverlo. */
    UNRESOLVED,
    NOT_FOUND,
}

/**
 * Crear, editar y borrar. En un calendario local el cambio va directo a la base y nunca toca un
 * servidor; en uno sincronizado se aplica en el celu al instante y queda en la cola para subirse.
 */
object Events {
    /** Borrador del evento para el formulario, o el motivo por el que no se puede editar. */
    suspend fun draftFor(item: AgendaItem, zone: ZoneId): Pair<EventDraft?, EditBlock?> = withContext(Dispatchers.IO) {
        if (item.calendar.readOnly) return@withContext null to EditBlock.READ_ONLY
        val row = Repo.event(item.calendar.id, item.occurrence.uid) ?: return@withContext null to EditBlock.NOT_FOUND
        val resource = try {
            Ics.parseResource(row.ics)
        } catch (e: IcsException) {
            return@withContext null to EditBlock.NOT_FOUND
        }
        if (IcsWriter.isSeries(resource)) return@withContext null to EditBlock.SERIES
        val change = Repo.changeFor(item.calendar.id, item.occurrence.uid)
        if (change != null && change.change.state != ChangeState.QUEUED) return@withContext null to EditBlock.UNRESOLVED
        IcsWriter.draftOf(resource, item.occurrence.uid, zone) to null
    }

    suspend fun create(calendar: CalendarInfo, draft: EventDraft) = withContext(Dispatchers.IO) {
        require(!calendar.readOnly)
        val uid = IcsWriter.newUid()
        val ics = IcsWriter.create(uid, draft)
        if (calendar.local) {
            Repo.putEvent(calendar.id, uid, null, null, ics)
            return@withContext
        }
        val base = Repo.calendarUrl(calendar.id) ?: return@withContext
        // Nombre del archivo: solo la parte aleatoria del UID (sin "@").
        val url = base + uid.substringBefore('@') + ".ics"
        Repo.putEvent(calendar.id, uid, url, null, ics)
        Repo.enqueue(calendar.id, PendingChange(0, base, uid, ChangeKind.CREATE, url, ics, null), draft.summary)
        SyncScheduler.pushSoon()
    }

    suspend fun update(calendar: CalendarInfo, uid: String, before: EventDraft, draft: EventDraft) = withContext(Dispatchers.IO) {
        require(!calendar.readOnly)
        val row = Repo.event(calendar.id, uid) ?: return@withContext
        val ics = IcsWriter.patch(row.ics, uid, before, draft)
        if (calendar.local) {
            Repo.putEvent(calendar.id, uid, null, null, ics)
            return@withContext
        }
        val base = Repo.calendarUrl(calendar.id) ?: return@withContext
        // En la caché queda el ETag del servidor (la base del cambio) hasta que se suba.
        Repo.putEvent(calendar.id, uid, row.url, row.etag, ics)
        Repo.enqueue(calendar.id, PendingChange(0, base, uid, ChangeKind.UPDATE, row.url ?: (base + uid.substringBefore('@') + ".ics"), ics, row.etag), draft.summary)
        SyncScheduler.pushSoon()
    }

    suspend fun delete(calendar: CalendarInfo, uid: String, summary: String?) = withContext(Dispatchers.IO) {
        require(!calendar.readOnly)
        if (calendar.local) {
            Repo.removeEvent(calendar.id, uid)
            return@withContext
        }
        val row = Repo.event(calendar.id, uid) ?: return@withContext
        val base = Repo.calendarUrl(calendar.id) ?: return@withContext
        Repo.markDeleted(calendar.id, uid)
        Repo.enqueue(calendar.id, PendingChange(0, base, uid, ChangeKind.DELETE, row.url ?: return@withContext, null, row.etag), summary)
        SyncScheduler.pushSoon()
    }
}
