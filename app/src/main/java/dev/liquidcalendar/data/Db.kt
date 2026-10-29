package dev.liquidcalendar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.liquidcalendar.R
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
                UNIQUE (calendar_id, uid)
            )""",
        )
        db.execSQL("CREATE INDEX event_url ON event (calendar_id, url)")

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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val NAME = "calendar.db"
        const val VERSION = 1
        const val LOCAL_ACCOUNT = 1L
        const val KIND_LOCAL = "local"
        const val KIND_CALDAV = "caldav"
        const val DEFAULT_COLOR = 0xFF8AB4F8.toInt()
    }
}
