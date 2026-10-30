package dev.liquidcalendar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.Recurrence
import dev.liquidcalendar.util.str

/**
 * Caché local en SQLite (carpeta privada de la app, fuera de las copias de seguridad). La
 * cuenta [LOCAL_ACCOUNT] es "En este celu": sus calendarios nunca se suben a ningún servidor.
 *
 * Pensada para poder cifrarla más adelante con SQLCipher: todo el acceso pasa por esta clase y
 * por [Repo], sin SQL desperdigado por la app.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE account (
                id INTEGER PRIMARY KEY,
                kind TEXT NOT NULL,
                server_url TEXT,
                username TEXT,
                principal_url TEXT,
                home_url TEXT,
                state TEXT NOT NULL DEFAULT 'ok',
                last_error TEXT,
                last_sync INTEGER,
                retry_at INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL(
            """CREATE TABLE calendar (
                id INTEGER PRIMARY KEY,
                account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                url TEXT,
                name TEXT NOT NULL,
                color INTEGER NOT NULL,
                visible INTEGER NOT NULL DEFAULT 1,
                read_only INTEGER NOT NULL DEFAULT 0,
                ctag TEXT,
                sync_token TEXT,
                UNIQUE (account_id, url)
            )""",
        )
        // Un recurso iCalendar por fila. Upsert por (calendario, UID): un mismo UID nunca queda
        // duplicado dentro de un calendario (y cada calendario pertenece a una sola cuenta).
        db.execSQL(
            """CREATE TABLE event (
                id INTEGER PRIMARY KEY,
                calendar_id INTEGER NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
                url TEXT,
                uid TEXT NOT NULL,
                etag TEXT,
                ics TEXT NOT NULL,
                start_ms INTEGER,
                end_ms INTEGER,
                UNIQUE (calendar_id, uid)
            )""",
        )
        db.execSQL("CREATE INDEX event_url ON event (calendar_id, url)")
        db.execSQL(RANGE_INDEX)
        createV3(db)
        createV4(db)

        db.insert("account", null, ContentValues().apply {
            put("id", LOCAL_ACCOUNT)
            put("kind", KIND_LOCAL)
        })
        db.insert("calendar", null, ContentValues().apply {
            put("account_id", LOCAL_ACCOUNT)
            put("name", str(R.string.default_local_calendar))
            put("color", DEFAULT_COLOR)
        })
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Cota de tiempo de cada evento, para traer solo lo que puede caer en la vista.
            db.execSQL("ALTER TABLE event ADD COLUMN start_ms INTEGER")
            db.execSQL("ALTER TABLE event ADD COLUMN end_ms INTEGER")
            db.execSQL(RANGE_INDEX)
            db.rawQuery("SELECT id, ics FROM event", null).use { c ->
                while (c.moveToNext()) {
                    val (start, end) = eventBounds(c.getString(1))
                    db.update("event", ContentValues().apply {
                        if (start != null) put("start_ms", start) else putNull("start_ms")
                        if (end != null) put("end_ms", end) else putNull("end_ms")
                    }, "id = ?", arrayOf(c.getLong(0).toString()))
                }
            }
        }
        if (oldVersion < 3) createV3(db)
        if (oldVersion < 4) createV4(db)
    }

    /**
     * v4: copia del iCalendar como estaba antes del primer cambio sin confirmar, para deshacer o
     * reintentar sin perder nada (por ejemplo, una serie entera) hasta que el servidor lo acepte.
     */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE change ADD COLUMN original_ics TEXT")
        db.execSQL("ALTER TABLE change ADD COLUMN original_etag TEXT")
    }

    /**
     * v3: cambios locales pendientes de subir. [change] es la cola (persistente; el id da el
     * orden). En event, deleted = borrado en el celu y pendiente de borrar en el servidor (no se
     * muestra). Un evento con filas en change no se pisa al sincronizar.
     */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE event ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """CREATE TABLE change (
                id INTEGER PRIMARY KEY,
                calendar_id INTEGER NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
                uid TEXT NOT NULL,
                kind TEXT NOT NULL,
                url TEXT NOT NULL,
                ics TEXT,
                base_etag TEXT,
                state TEXT NOT NULL DEFAULT 'QUEUED',
                error TEXT,
                server_ics TEXT,
                server_etag TEXT,
                summary TEXT,
                created_at INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX change_uid ON change (calendar_id, uid)")
    }

    companion object {
        const val NAME = "calendar.db"
        const val VERSION = 4
        const val LOCAL_ACCOUNT = 1L
        const val KIND_LOCAL = "local"
        const val KIND_CALDAV = "caldav"
        const val DEFAULT_COLOR = 0xFF8AB4F8.toInt()
        private const val RANGE_INDEX = "CREATE INDEX event_range ON event (start_ms, end_ms)"

        /**
         * Inicio y fin aproximados (en ms) donde puede caer el recurso. Fin null = serie sin fin;
         * los dos null = archivo ilegible (se trae siempre y la expansión lo descarta).
         */
        fun eventBounds(ics: String): Pair<Long?, Long?> = try {
            val b = Recurrence.bounds(Ics.parseResource(ics))
            (b?.start?.toEpochMilli()) to (b?.end?.toEpochMilli())
        } catch (e: IcsException) {
            null to null
        }
    }
}
