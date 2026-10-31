package dev.liquidcalendar.data

import dev.liquidcalendar.core.ics.DraftTime
import dev.liquidcalendar.core.ics.EventDraft
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.IcsWriter
import dev.liquidcalendar.core.ics.ImportedEvent
import dev.liquidcalendar.core.ics.Moment
import dev.liquidcalendar.core.ics.Series
import dev.liquidcalendar.core.ics.SeriesScope
import dev.liquidcalendar.core.ics.When
import dev.liquidcalendar.core.sync.ChangeKind
import dev.liquidcalendar.core.sync.ChangeState
import dev.liquidcalendar.core.sync.PendingChange
import dev.liquidcalendar.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID
import dev.liquidcalendar.core.ics.Repeats

private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,120}")

/** Por qué no se puede editar o borrar un evento (null = se puede). */
enum class EditBlock {
    READ_ONLY,

    /** Tiene un cambio en conflicto o fallido: primero hay que resolverlo. */
    UNRESOLVED,
    NOT_FOUND,
}

/** Lo que el formulario necesita para editar: el borrador y, si es una serie, qué ocurrencia es. */
data class Editable(val draft: EventDraft, val occurrence: Moment?) {
    val isSeries: Boolean get() = occurrence != null
}

/**
 * Crear, editar y borrar. En un calendario local el cambio va directo a la base y nunca toca un
 * servidor; en uno sincronizado se aplica en el celu al instante y queda en la cola para subirse,
 * con una copia de cómo estaba antes hasta que el servidor lo confirme.
 */
object Events {
    suspend fun editable(item: AgendaItem, zone: ZoneId): Pair<Editable?, EditBlock?> = withContext(Dispatchers.IO) {
        if (item.calendar.readOnly) return@withContext null to EditBlock.READ_ONLY
        val row = Repo.event(item.calendar.id, item.occurrence.uid) ?: return@withContext null to EditBlock.NOT_FOUND
        val resource = try {
            Ics.parseResource(row.ics)
        } catch (e: IcsException) {
            return@withContext null to EditBlock.NOT_FOUND
        }
        val change = Repo.changeFor(item.calendar.id, item.occurrence.uid)
        if (change != null && change.change.state != ChangeState.QUEUED) return@withContext null to EditBlock.UNRESOLVED
        if (!IcsWriter.isSeries(resource)) {
            val draft = IcsWriter.draftOf(resource, item.occurrence.uid, zone) ?: return@withContext null to EditBlock.NOT_FOUND
            return@withContext Editable(draft, null) to null
        }
        // Serie: el borrador es el de esta ocurrencia (su horario, sus textos y sus recordatorios).
        val occ = item.occurrence
        val time = when (val t = occ.time) {
            is When.AllDay -> DraftTime.AllDay(t.first, t.endExclusive.minusDays(1))
            is When.Timed -> DraftTime.Timed(t.start, t.end)
        }
        // La repetición es la de la serie (la del evento principal).
        val repeat = Repeats.of(resource, item.occurrence.uid, zone)
        Editable(EventDraft(occ.summary.orEmpty(), occ.location, occ.description, time, occ.alarms, repeat), occ.original ?: return@withContext null to EditBlock.NOT_FOUND) to null
    }

    /** Cuántas ocurrencias toca cada alcance (null = sin fin). */
    suspend fun affected(calendar: CalendarInfo, uid: String, occurrence: Moment, scope: SeriesScope, zone: ZoneId): Int? = withContext(Dispatchers.Default) {
        val row = withContext(Dispatchers.IO) { Repo.event(calendar.id, uid) } ?: return@withContext 0
        Series.affected(Ics.parseResource(row.ics), uid, occurrence, scope, zone)
    }

    /** Cuántos cambios a ocurrencias sueltas se pierden si se cambia la repetición con ese alcance. */
    suspend fun discardedByRuleChange(calendar: CalendarInfo, uid: String, occurrence: Moment, scope: SeriesScope, zone: ZoneId): Int = withContext(Dispatchers.Default) {
        val row = withContext(Dispatchers.IO) { Repo.event(calendar.id, uid) } ?: return@withContext 0
        Series.discardedByRuleChange(row.ics, uid, occurrence, scope, zone)
    }

    suspend fun create(calendar: CalendarInfo, draft: EventDraft, zone: ZoneId) = withContext(Dispatchers.IO) {
        require(!calendar.readOnly)
        val uid = IcsWriter.newUid()
        val ics = IcsWriter.create(uid, draft, zone = zone)
        writeNew(calendar, uid, ics, draft.summary)
    }

    suspend fun update(calendar: CalendarInfo, uid: String, before: EventDraft, draft: EventDraft, zone: ZoneId) = withContext(Dispatchers.IO) {
        require(!calendar.readOnly)
        val row = Repo.event(calendar.id, uid) ?: return@withContext
        writeExisting(calendar, row, IcsWriter.patch(row.ics, uid, before, draft, zone = zone), draft.summary)
    }

    /** Edita una serie con el alcance elegido (solo esta, esta y las siguientes, todas). */
    suspend fun updateSeries(calendar: CalendarInfo, uid: String, occurrence: Moment, scope: SeriesScope, before: EventDraft, draft: EventDraft, zone: ZoneId) =
        withContext(Dispatchers.IO) {
            require(!calendar.readOnly)
            val row = Repo.event(calendar.id, uid) ?: return@withContext
            val result = Series.edit(row.ics, uid, occurrence, scope, before, draft, zone)
            applySeries(calendar, row, uid, result.original, result.created, draft.summary)
        }

    suspend fun deleteSeries(calendar: CalendarInfo, uid: String, occurrence: Moment, scope: SeriesScope, summary: String?, zone: ZoneId) =
        withContext(Dispatchers.IO) {
            require(!calendar.readOnly)
            val row = Repo.event(calendar.id, uid) ?: return@withContext
            val result = Series.delete(row.ics, uid, occurrence, scope, zone)
            applySeries(calendar, row, uid, result.original, null, summary)
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
        Repo.enqueue(calendar.id, PendingChange(0, base, uid, ChangeKind.DELETE, row.url ?: return@withContext, null, row.etag), summary, row.ics, row.etag)
        SyncScheduler.pushSoon()
    }

    // --- Apoyo ---

    private fun applySeries(calendar: CalendarInfo, row: Repo.EventRow, uid: String, original: String?, created: Pair<String, String>?, summary: String?) {
        if (original == null) {
            // La serie entera desaparece (borrar todas, o "esta y las siguientes" desde la primera).
            if (calendar.local) Repo.removeEvent(calendar.id, uid) else {
                val base = Repo.calendarUrl(calendar.id) ?: return
                Repo.markDeleted(calendar.id, uid)
                Repo.enqueue(calendar.id, PendingChange(0, base, uid, ChangeKind.DELETE, row.url ?: return, null, row.etag), summary, row.ics, row.etag)
            }
        }
        val cut = if (original != null) writeExisting(calendar, row, original, summary, schedule = false) else null
        // La serie nueva se sube solo después de que el servidor aceptó el corte: si el corte queda
        // en conflicto, la nueva espera (así nunca quedan ocurrencias duplicadas en el servidor).
        created?.let { (newUid, ics) -> writeNew(calendar, newUid, ics, summary, schedule = false, dependsOn = cut) }
        if (!calendar.local) SyncScheduler.pushSoon()
    }

    /**
     * Importa eventos de un .ics salteando los UID que ya están en el calendario (sin pisar nada).
     * Devuelve cuántos se agregaron. En un calendario sincronizado quedan en la cola para subirse.
     */
    suspend fun importInto(calendar: CalendarInfo, events: List<ImportedEvent>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (e in events) {
            if (Repo.event(calendar.id, e.uid) != null) continue
            writeNew(calendar, e.uid, e.ics, e.summary, schedule = false)
            added++
        }
        if (added > 0 && !calendar.local) SyncScheduler.pushSoon()
        added
    }

    private fun writeNew(calendar: CalendarInfo, uid: String, ics: String, summary: String?, schedule: Boolean = true, dependsOn: Long? = null) {
        if (calendar.local) {
            Repo.putEvent(calendar.id, uid, null, null, ics)
            return
        }
        val base = Repo.calendarUrl(calendar.id) ?: return
        // Nombre del archivo: la parte del UID antes de "@" si es segura; si no (UID importados), uno aleatorio.
        val name = uid.substringBefore('@').takeIf { it.matches(SAFE_NAME) } ?: UUID.randomUUID().toString()
        val url = "$base$name.ics"
        Repo.putEvent(calendar.id, uid, url, null, ics)
        Repo.enqueue(calendar.id, PendingChange(0, base, uid, ChangeKind.CREATE, url, ics, null, dependsOn = dependsOn), summary)
        if (schedule) SyncScheduler.pushSoon()
    }

    /** Devuelve el id del cambio encolado (null en un calendario local). */
    private fun writeExisting(calendar: CalendarInfo, row: Repo.EventRow, ics: String, summary: String?, schedule: Boolean = true): Long? {
        if (calendar.local) {
            Repo.putEvent(calendar.id, row.uid, null, null, ics)
            return null
        }
        val base = Repo.calendarUrl(calendar.id) ?: return null
        // En la caché queda el ETag del servidor (la base del cambio) hasta que se suba.
        Repo.putEvent(calendar.id, row.uid, row.url, row.etag, ics)
        val id = Repo.enqueue(
            calendar.id,
            PendingChange(0, base, row.uid, ChangeKind.UPDATE, row.url ?: (base + row.uid.substringBefore('@') + ".ics"), ics, row.etag),
            summary,
            originalIcs = row.ics,
            originalEtag = row.etag,
        )
        if (schedule) SyncScheduler.pushSoon()
        return id
    }
}
