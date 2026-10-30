package dev.liquidcalendar.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.core.database.sqlite.transaction
import dev.liquidcalendar.core.dav.RemoteCalendar
import dev.liquidcalendar.core.sync.ChangeKind
import dev.liquidcalendar.core.sync.ChangeQueue
import dev.liquidcalendar.core.sync.ChangeState
import dev.liquidcalendar.core.sync.ChangeStore
import dev.liquidcalendar.core.sync.PendingChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccountState(val db: String) {
    OK("ok"),

    /** El servidor respondió 401: no se sincroniza hasta que el usuario vuelva a ingresar la contraseña. */
    NEEDS_PASSWORD("needs_password"),

    /** Último intento fallido (red, servidor...): ver lastError y retryAt. */
    ERROR("error");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.db == s } ?: OK
    }
}

data class Account(
    val id: Long,
    val kind: String,
    val serverUrl: String?,
    val username: String?,
    val homeUrl: String?,
    val state: AccountState,
    /** Tipo de error del último intento (nombre de la DavException), sin datos personales. */
    val lastError: String?,
    val lastSync: Long?,
    /** No intentar antes de este instante (Retry-After o espera exponencial). */
    val retryAt: Long?,
    val attempts: Int,
) {
    val isLocal: Boolean get() = kind == Db.KIND_LOCAL
}

/** Lo que se muestra de un calendario en cualquier vista: siempre se sabe si es local o de un servidor. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val color: Int,
    /** true = "En este celu" (nunca se sube); false = sincronizado con [host]. */
    val local: Boolean,
    val host: String?,
    val readOnly: Boolean,
)

data class CalendarRow(
    val id: Long,
    val accountId: Long,
    val url: String?,
    val name: String,
    val color: Int,
    val visible: Boolean,
    val readOnly: Boolean,
    val events: Int,
)

/** Acceso a la caché. [version] cambia con cada escritura, para que las pantallas se refresquen. */
object Repo {
    private lateinit var db: Db
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun init(context: Context) {
        db = Db(context)
    }

    private fun changed() {
        _version.value++
    }

    // --- Cuentas ---

    fun accounts(): List<Account> = db.readableDatabase.rawQuery("SELECT * FROM account ORDER BY id", null).use { c ->
        generateSequence { if (c.moveToNext()) c.account() else null }.toList()
    }

    fun account(id: Long): Account? = accounts().firstOrNull { it.id == id }

    fun caldavAccount(): Account? = accounts().firstOrNull { !it.isLocal }

    /** Crea o actualiza la cuenta CalDAV. Volver a guardarla la deja lista para sincronizar. */
    fun saveCaldavAccount(id: Long?, serverUrl: String, username: String, principalUrl: String, homeUrl: String): Long {
        val values = ContentValues().apply {
            put("kind", Db.KIND_CALDAV)
            put("server_url", serverUrl)
            put("username", username)
            put("principal_url", principalUrl)
            put("home_url", homeUrl)
            put("state", AccountState.OK.db)
            putNull("last_error")
            putNull("retry_at")
            put("attempts", 0)
        }
        val w = db.writableDatabase
        val result = if (id == null) {
            w.insertOrThrow("account", null, values)
        } else {
            w.update("account", values, "id = ?", arrayOf(id.toString()))
            id
        }
        changed()
        return result
    }

    fun updateSyncState(id: Long, state: AccountState, lastError: String?, retryAt: Long?, attempts: Int, lastSync: Long? = null) {
        db.writableDatabase.update(
            "account",
            ContentValues().apply {
                put("state", state.db)
                if (lastError != null) put("last_error", lastError) else putNull("last_error")
                if (retryAt != null) put("retry_at", retryAt) else putNull("retry_at")
                put("attempts", attempts)
                if (lastSync != null) put("last_sync", lastSync)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
        changed()
    }

    /** Borra la cuenta y su caché (calendarios y eventos) del celu. En el servidor no toca nada. */
    fun deleteAccount(id: Long) {
        require(id != Db.LOCAL_ACCOUNT)
        db.writableDatabase.delete("account", "id = ?", arrayOf(id.toString()))
        changed()
    }

    // --- Calendarios ---

    fun calendars(): List<CalendarRow> = db.readableDatabase.rawQuery(
        """SELECT c.*, (SELECT COUNT(*) FROM event e WHERE e.calendar_id = c.id) AS events
           FROM calendar c ORDER BY c.account_id, c.name COLLATE NOCASE""",
        null,
    ).use { c -> generateSequence { if (c.moveToNext()) c.calendar() else null }.toList() }

    fun addLocalCalendar(name: String, color: Int): Long {
        val id = db.writableDatabase.insertOrThrow("calendar", null, ContentValues().apply {
            put("account_id", Db.LOCAL_ACCOUNT)
            put("name", name)
            put("color", color)
        })
        changed()
        return id
    }

    fun setVisible(calendarId: Long, visible: Boolean) {
        db.writableDatabase.update("calendar", ContentValues().apply { put("visible", if (visible) 1 else 0) }, "id = ?", arrayOf(calendarId.toString()))
        changed()
    }

    /**
     * Deja los calendarios de la cuenta igual que en el servidor: agrega los nuevos, actualiza
     * nombre, color y permisos (conservando si se muestran o no) y quita los que ya no existen.
     */
    fun replaceRemoteCalendars(accountId: Long, remote: List<RemoteCalendar>, fallbackName: (RemoteCalendar) -> String) {
        db.writableDatabase.transaction {
            val w = this
            val existing = calendars().filter { it.accountId == accountId }.associateBy { it.url }
            for (r in remote) {
                val values = ContentValues().apply {
                    put("name", r.name ?: fallbackName(r))
                    put("color", r.color ?: Db.DEFAULT_COLOR)
                    put("read_only", if (r.readOnly) 1 else 0)
                }
                val current = existing[r.url]
                if (current == null) {
                    values.put("account_id", accountId)
                    values.put("url", r.url)
                    w.insertOrThrow("calendar", null, values)
                } else {
                    w.update("calendar", values, "id = ?", arrayOf(current.id.toString()))
                }
            }
            val keep = remote.map { it.url }.toSet()
            existing.values.filter { it.url !in keep }.forEach { w.delete("calendar", "id = ?", arrayOf(it.id.toString())) }
        }
        changed()
    }

    fun setCalendarTags(calendarId: Long, ctag: String?, syncToken: String?) {
        db.writableDatabase.update(
            "calendar",
            ContentValues().apply {
                if (ctag != null) put("ctag", ctag) else putNull("ctag")
                if (syncToken != null) put("sync_token", syncToken) else putNull("sync_token")
            },
            "id = ?",
            arrayOf(calendarId.toString()),
        )
    }

    fun calendarCtag(calendarId: Long): String? =
        db.readableDatabase.rawQuery("SELECT ctag FROM calendar WHERE id = ?", arrayOf(calendarId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    // --- Eventos ---

    /** URL → ETag de los eventos guardados de un calendario. */
    fun eventEtags(calendarId: Long): Map<String, String?> =
        // Los eventos con cambios sin subir no se comparan: la sincronización no los pisa ni los borra.
        db.readableDatabase.rawQuery(
            "SELECT url, etag FROM event WHERE calendar_id = ? AND url IS NOT NULL AND uid NOT IN (SELECT uid FROM change WHERE calendar_id = ?)",
            arrayOf(calendarId.toString(), calendarId.toString()),
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), if (c.isNull(1)) null else c.getString(1)) }
        }

    data class StoredEvent(val url: String?, val uid: String, val etag: String?, val ics: String)

    /** Aplica en una transacción los eventos nuevos o cambiados y los borrados en el servidor. */
    fun applyEvents(calendarId: Long, upserts: List<StoredEvent>, deletedUrls: Collection<String>) {
        if (upserts.isEmpty() && deletedUrls.isEmpty()) return
        db.writableDatabase.transaction {
            val w = this
            deletedUrls.forEach { w.delete("event", "calendar_id = ? AND url = ?", arrayOf(calendarId.toString(), it)) }
            val statement = w.compileStatement(
                """INSERT INTO event (calendar_id, url, uid, etag, ics, start_ms, end_ms) VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (calendar_id, uid) DO UPDATE SET url = excluded.url, etag = excluded.etag,
                   ics = excluded.ics, start_ms = excluded.start_ms, end_ms = excluded.end_ms""",
            )
            for (e in upserts) {
                statement.clearBindings()
                statement.bindLong(1, calendarId)
                if (e.url != null) statement.bindString(2, e.url) else statement.bindNull(2)
                statement.bindString(3, e.uid)
                if (e.etag != null) statement.bindString(4, e.etag) else statement.bindNull(4)
                statement.bindString(5, e.ics)
                val (start, end) = Db.eventBounds(e.ics)
                if (start != null) statement.bindLong(6, start) else statement.bindNull(6)
                if (end != null) statement.bindLong(7, end) else statement.bindNull(7)
                statement.executeInsert()
            }
        }
        changed()
    }

    /** Un evento guardado que puede caer en [fromMs, toMs), de un calendario visible. */
    data class Candidate(val id: Long, val calendarId: Long, val etag: String?, val ics: String)

    fun candidates(fromMs: Long, toMs: Long): List<Candidate> = db.readableDatabase.rawQuery(
        """SELECT e.id, e.calendar_id, e.etag, e.ics FROM event e JOIN calendar c ON c.id = e.calendar_id
           WHERE c.visible = 1 AND e.deleted = 0 AND (e.start_ms IS NULL OR e.start_ms < ?) AND (e.end_ms IS NULL OR e.end_ms > ?)""",
        arrayOf(toMs.toString(), fromMs.toString()),
    ).use { c ->
        generateSequence { if (c.moveToNext()) Candidate(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getString(2), c.getString(3)) else null }.toList()
    }

    /** Calendarios con su cuenta, para mostrar el nombre, el color y si es local o de un servidor. */
    fun calendarInfos(): Map<Long, CalendarInfo> {
        val hosts = accounts().associate { a ->
            a.id to a.serverUrl?.let { (dev.liquidcalendar.core.ServerUrl.parse(it, allowHttp = true) as? dev.liquidcalendar.core.ServerUrl.Result.Ok)?.url?.host }
        }
        val locals = accounts().filter { it.isLocal }.map { it.id }.toSet()
        return calendars().associate { c ->
            c.id to CalendarInfo(c.id, c.name, c.color, local = c.accountId in locals, host = hosts[c.accountId], readOnly = c.readOnly)
        }
    }

    // --- Eventos guardados y cola de cambios ---

    data class EventRow(val id: Long, val calendarId: Long, val url: String?, val uid: String, val etag: String?, val ics: String, val deleted: Boolean)

    fun event(calendarId: Long, uid: String): EventRow? = db.readableDatabase.rawQuery(
        "SELECT id, calendar_id, url, uid, etag, ics, deleted FROM event WHERE calendar_id = ? AND uid = ?",
        arrayOf(calendarId.toString(), uid),
    ).use { c -> if (c.moveToFirst()) EventRow(c.getLong(0), c.getLong(1), c.str("url"), c.getString(3), c.str("etag"), c.getString(5), c.getLong(6) == 1L) else null }

    fun calendarUrl(calendarId: Long): String? =
        db.readableDatabase.rawQuery("SELECT url FROM calendar WHERE id = ?", arrayOf(calendarId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** Guarda un evento en la caché (local o con cambio pendiente). No toca la cola. */
    fun putEvent(calendarId: Long, uid: String, url: String?, etag: String?, ics: String) {
        applyEvents(calendarId, listOf(StoredEvent(url, uid, etag, ics)), emptyList())
    }

    fun removeEvent(calendarId: Long, uid: String) {
        db.writableDatabase.delete("event", "calendar_id = ? AND uid = ?", arrayOf(calendarId.toString(), uid))
        changed()
    }

    /** Oculta el evento (borrado en el celu, pendiente de borrar en el servidor). */
    fun markDeleted(calendarId: Long, uid: String) {
        db.writableDatabase.update("event", ContentValues().apply { put("deleted", 1) }, "calendar_id = ? AND uid = ?", arrayOf(calendarId.toString(), uid))
        changed()
    }

    /** UIDs con cambios sin subir (o en conflicto/fallidos): la sincronización no los pisa. */
    fun pendingUids(calendarId: Long): Set<String> =
        db.readableDatabase.rawQuery("SELECT uid FROM change WHERE calendar_id = ?", arrayOf(calendarId.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    data class ChangeRow(
        val change: PendingChange,
        val calendarId: Long,
        val summary: String?,
        val createdAt: Long,
        /** Cómo estaba el evento antes del primer cambio sin confirmar (para deshacer). */
        val originalIcs: String? = null,
        val originalEtag: String? = null,
    )

    private fun Cursor.change(): ChangeRow = ChangeRow(
        PendingChange(
            id = long("id")!!,
            calendarUrl = str("calendar_url").orEmpty(),
            uid = str("uid")!!,
            kind = ChangeKind.valueOf(str("kind")!!),
            url = str("url")!!,
            ics = str("ics"),
            baseEtag = str("base_etag"),
            state = ChangeState.valueOf(str("state")!!),
            error = str("error"),
            serverIcs = str("server_ics"),
            serverEtag = str("server_etag"),
        ),
        calendarId = long("calendar_id")!!,
        summary = str("summary"),
        createdAt = long("created_at")!!,
        originalIcs = str("original_ics"),
        originalEtag = str("original_etag"),
    )

    private const val CHANGE_SELECT = "SELECT ch.*, c.url AS calendar_url FROM change ch JOIN calendar c ON c.id = ch.calendar_id"

    fun changes(): List<ChangeRow> = db.readableDatabase.rawQuery("$CHANGE_SELECT ORDER BY ch.id", null).use { c ->
        generateSequence { if (c.moveToNext()) c.change() else null }.toList()
    }

    fun changeFor(calendarId: Long, uid: String): ChangeRow? =
        db.readableDatabase.rawQuery("$CHANGE_SELECT WHERE ch.calendar_id = ? AND ch.uid = ? ORDER BY ch.id DESC LIMIT 1", arrayOf(calendarId.toString(), uid)).use { c ->
            if (c.moveToFirst()) c.change() else null
        }

    /**
     * Agrega un cambio a la cola, combinándolo con uno anterior del mismo evento que todavía no
     * salió (crear + editar = crear con lo último, crear + borrar = nada, etc.).
     */
    fun enqueue(calendarId: Long, change: PendingChange, summary: String?, originalIcs: String? = null, originalEtag: String? = null) {
        db.writableDatabase.transaction {
            val previous = rawQuery(
                "SELECT ch.*, c.url AS calendar_url FROM change ch JOIN calendar c ON c.id = ch.calendar_id WHERE ch.calendar_id = ? AND ch.uid = ? AND ch.state = 'QUEUED' ORDER BY ch.id DESC LIMIT 1",
                arrayOf(calendarId.toString(), change.uid),
            ).use { c -> if (c.moveToFirst()) c.change() else null }
            if (previous == null) {
                insertOrThrow("change", null, changeValues(calendarId, change, summary).apply {
                    put("created_at", System.currentTimeMillis())
                    // La versión de antes del primer cambio: se conserva aunque se encolen más cambios.
                    if (originalIcs != null) put("original_ics", originalIcs)
                    if (originalEtag != null) put("original_etag", originalEtag)
                })
            } else {
                val merged = ChangeQueue.merge(previous.change, change).result
                if (merged == null) {
                    // Se creó y se borró sin llegar al servidor: no queda nada, ni en la cola ni en el celu.
                    delete("change", "id = ?", arrayOf(previous.change.id.toString()))
                    delete("event", "calendar_id = ? AND uid = ?", arrayOf(calendarId.toString(), change.uid))
                } else {
                    update("change", changeValues(calendarId, merged, summary), "id = ?", arrayOf(previous.change.id.toString()))
                }
            }
        }
        changed()
    }

    private fun changeValues(calendarId: Long, c: PendingChange, summary: String?) = ContentValues().apply {
        put("calendar_id", calendarId)
        put("uid", c.uid)
        put("kind", c.kind.name)
        put("url", c.url)
        if (c.ics != null) put("ics", c.ics) else putNull("ics")
        if (c.baseEtag != null) put("base_etag", c.baseEtag) else putNull("base_etag")
        put("state", c.state.name)
        if (c.error != null) put("error", c.error) else putNull("error")
        if (c.serverIcs != null) put("server_ics", c.serverIcs) else putNull("server_ics")
        if (c.serverEtag != null) put("server_etag", c.serverEtag) else putNull("server_etag")
        if (summary != null) put("summary", summary) else putNull("summary")
    }

    /** La próxima sincronización vuelve a comparar todos los eventos de ese calendario. */
    private fun android.database.sqlite.SQLiteDatabase.forgetCtag(calendarId: Long) {
        update("calendar", ContentValues().apply { putNull("ctag") }, "id = ?", arrayOf(calendarId.toString()))
    }

    /** La cola de una cuenta, para el Pusher. */
    fun changeStore(accountId: Long): ChangeStore = object : ChangeStore {
        override fun queued(): List<PendingChange> = db.readableDatabase.rawQuery(
            "$CHANGE_SELECT WHERE c.account_id = ? AND ch.state = 'QUEUED' ORDER BY ch.id",
            arrayOf(accountId.toString()),
        ).use { c -> generateSequence { if (c.moveToNext()) c.change().change else null }.toList() }

        override fun done(change: PendingChange, etag: String?) {
            db.writableDatabase.transaction {
                val calendarId = rawQuery("SELECT calendar_id FROM change WHERE id = ?", arrayOf(change.id.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                delete("change", "id = ?", arrayOf(change.id.toString()))
                if (calendarId != null) {
                    if (change.kind == ChangeKind.DELETE) {
                        delete("event", "calendar_id = ? AND uid = ?", arrayOf(calendarId.toString(), change.uid))
                    } else {
                        // ETag nuevo; si el servidor no lo informó, queda null y la próxima
                        // sincronización trae la versión del servidor.
                        update("event", ContentValues().apply { if (etag != null) put("etag", etag) else putNull("etag") }, "calendar_id = ? AND uid = ?", arrayOf(calendarId.toString(), change.uid))
                    }
                }
            }
            changed()
        }

        override fun conflict(change: PendingChange, serverIcs: String?, serverEtag: String?) {
            db.writableDatabase.update("change", ContentValues().apply {
                put("state", ChangeState.CONFLICT.name)
                if (serverIcs != null) put("server_ics", serverIcs) else putNull("server_ics")
                if (serverEtag != null) put("server_etag", serverEtag) else putNull("server_etag")
            }, "id = ?", arrayOf(change.id.toString()))
            changed()
        }

        override fun failed(change: PendingChange, error: dev.liquidcalendar.core.dav.DavException) {
            db.writableDatabase.update("change", ContentValues().apply {
                put("state", ChangeState.FAILED.name)
                put("error", error.code)
            }, "id = ?", arrayOf(change.id.toString()))
            changed()
        }
    }

    /**
     * Descarta un cambio: si era un evento nuevo, desaparece; si no, vuelve a mostrarse y su ETag
     * se borra para que la próxima sincronización traiga la versión del servidor.
     */
    fun discard(changeId: Long) {
        db.writableDatabase.transaction {
            val row = rawQuery("$CHANGE_SELECT WHERE ch.id = ?", arrayOf(changeId.toString())).use { c -> if (c.moveToFirst()) c.change() else null } ?: return@transaction
            delete("change", "id = ?", arrayOf(changeId.toString()))
            forgetCtag(row.calendarId)
            val args = arrayOf(row.calendarId.toString(), row.change.uid)
            if (row.change.kind == ChangeKind.CREATE) {
                delete("event", "calendar_id = ? AND uid = ?", args)
            } else if (row.originalIcs != null) {
                // Vuelve al instante a la versión de antes del cambio (la que se guardó en la cola).
                val (start, end) = Db.eventBounds(row.originalIcs)
                update("event", ContentValues().apply {
                    put("deleted", 0)
                    put("ics", row.originalIcs)
                    if (row.originalEtag != null) put("etag", row.originalEtag) else putNull("etag")
                    if (start != null) put("start_ms", start) else putNull("start_ms")
                    if (end != null) put("end_ms", end) else putNull("end_ms")
                }, "calendar_id = ? AND uid = ?", args)
            } else {
                update("event", ContentValues().apply { put("deleted", 0); putNull("etag") }, "calendar_id = ? AND uid = ?", args)
            }
        }
        changed()
    }

    /** Conflicto: quedarse con la versión del celu (se vuelve a subir sobre la del servidor). */
    fun keepMine(changeId: Long) {
        db.writableDatabase.transaction {
            val row = rawQuery("$CHANGE_SELECT WHERE ch.id = ?", arrayOf(changeId.toString())).use { c -> if (c.moveToFirst()) c.change() else null } ?: return@transaction
            val c = row.change
            val values = ContentValues().apply {
                put("state", ChangeState.QUEUED.name)
                putNull("error")
                when {
                    // El servidor ya no lo tiene: si era un borrado, ya está; si no, se vuelve a crear.
                    c.serverEtag == null && c.kind == ChangeKind.DELETE -> Unit
                    c.serverEtag == null -> { put("kind", ChangeKind.CREATE.name); putNull("base_etag") }
                    else -> put("base_etag", c.serverEtag)
                }
                putNull("server_ics")
                putNull("server_etag")
            }
            if (c.serverEtag == null && c.kind == ChangeKind.DELETE) {
                delete("change", "id = ?", arrayOf(changeId.toString()))
                delete("event", "calendar_id = ? AND uid = ?", arrayOf(row.calendarId.toString(), c.uid))
            } else {
                update("change", values, "id = ?", arrayOf(changeId.toString()))
            }
        }
        changed()
    }

    /** Conflicto: quedarse con la versión del servidor (se descarta el cambio del celu). */
    fun keepServer(changeId: Long) {
        db.writableDatabase.transaction {
            val row = rawQuery("$CHANGE_SELECT WHERE ch.id = ?", arrayOf(changeId.toString())).use { c -> if (c.moveToFirst()) c.change() else null } ?: return@transaction
            val c = row.change
            delete("change", "id = ?", arrayOf(changeId.toString()))
            forgetCtag(row.calendarId)
            val args = arrayOf(row.calendarId.toString(), c.uid)
            if (c.serverIcs == null) {
                delete("event", "calendar_id = ? AND uid = ?", args)
            } else {
                val (start, end) = Db.eventBounds(c.serverIcs!!)
                update("event", ContentValues().apply {
                    put("ics", c.serverIcs)
                    if (c.serverEtag != null) put("etag", c.serverEtag) else putNull("etag")
                    put("deleted", 0)
                    if (start != null) put("start_ms", start) else putNull("start_ms")
                    if (end != null) put("end_ms", end) else putNull("end_ms")
                }, "calendar_id = ? AND uid = ?", args)
            }
        }
        changed()
    }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.account() = Account(
        id = long("id")!!,
        kind = str("kind")!!,
        serverUrl = str("server_url"),
        username = str("username"),
        homeUrl = str("home_url"),
        state = AccountState.of(str("state")),
        lastError = str("last_error"),
        lastSync = long("last_sync"),
        retryAt = long("retry_at"),
        attempts = long("attempts")?.toInt() ?: 0,
    )

    private fun Cursor.calendar() = CalendarRow(
        id = long("id")!!,
        accountId = long("account_id")!!,
        url = str("url"),
        name = str("name")!!,
        color = long("color")!!.toInt(),
        visible = long("visible") == 1L,
        readOnly = long("read_only") == 1L,
        events = long("events")?.toInt() ?: 0,
    )
}
