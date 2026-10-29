package dev.liquidcalendar.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.core.database.sqlite.transaction
import dev.liquidcalendar.core.dav.RemoteCalendar
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
        db.readableDatabase.rawQuery("SELECT url, etag FROM event WHERE calendar_id = ? AND url IS NOT NULL", arrayOf(calendarId.toString())).use { c ->
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
           WHERE c.visible = 1 AND (e.start_ms IS NULL OR e.start_ms < ?) AND (e.end_ms IS NULL OR e.end_ms > ?)""",
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
