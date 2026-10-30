package dev.liquidcalendar.sync

import dev.liquidcalendar.BuildConfig
import dev.liquidcalendar.R
import dev.liquidcalendar.core.ServerUrl
import dev.liquidcalendar.core.dav.CalDav
import dev.liquidcalendar.core.dav.Credentials
import dev.liquidcalendar.core.dav.DavClient
import dev.liquidcalendar.core.dav.DavException
import dev.liquidcalendar.core.dav.Discovery
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.sync.LoginGuard
import dev.liquidcalendar.core.sync.Pusher
import dev.liquidcalendar.core.sync.RetryDecision
import dev.liquidcalendar.core.sync.RetryPolicy
import dev.liquidcalendar.data.AccountState
import dev.liquidcalendar.data.CredentialStore
import dev.liquidcalendar.data.JsonStore
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.util.AppLog
import dev.liquidcalendar.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import android.content.Context
import java.io.File
import java.time.Duration
import java.time.Instant

sealed interface SyncResult {
    data object Ok : SyncResult

    /** No hay cuenta CalDAV: la app funciona solo con los calendarios del celu. */
    data object NoAccount : SyncResult

    /** El servidor rechazó la contraseña: no se vuelve a intentar hasta que se edite la cuenta. */
    data object NeedsPassword : SyncResult

    /** El servidor pidió esperar (o hubo errores seguidos): no intentar antes de [until]. */
    data class Wait(val until: Instant) : SyncResult

    data class Failed(val error: DavException) : SyncResult
}

sealed interface TestResult {
    data class Ok(val discovery: Discovery) : TestResult
    data class Invalid(val reason: ServerUrl.Reason) : TestResult

    /** Demasiadas contraseñas incorrectas seguidas: esperar [wait] antes de volver a probar. */
    data class Blocked(val wait: Duration) : TestResult
    data class Failed(val error: DavException) : TestResult
}

/**
 * Sincronización con el servidor CalDAV. Una sola a la vez; ninguna regla de reintento se salta
 * por pedirla a mano: un 401 deja la cuenta en "requiere contraseña" y un Retry-After se respeta
 * también en el botón de sincronizar.
 */
object SyncEngine {
    private val mutex = Mutex()
    private val policy = RetryPolicy()
    private lateinit var credentials: CredentialStore

    /** Contraseñas rechazadas recientemente (persistido, para que reiniciar la app no lo borre). */
    private val guard = LoginGuard()
    private lateinit var guardStore: JsonStore<List<Long>>

    fun init(context: Context) {
        credentials = CredentialStore(context)
        guardStore = JsonStore(File(context.noBackupFilesDir, "login-failures.json"), ListSerializer(Long.serializer()), emptyList())
        guard.restore(guardStore.value.map(Instant::ofEpochMilli))
    }

    val credentialStore: CredentialStore get() = credentials

    /**
     * Solo https. Las compilaciones de prueba (nunca la release) aceptan además http hacia el
     * propio celu, para el Radicale local reenviado con adb reverse.
     */
    private fun parseServer(url: String): ServerUrl.Result {
        val r = ServerUrl.parse(url, allowHttp = BuildConfig.LOCALHOST_HTTP)
        if (r is ServerUrl.Result.Ok && !r.url.isHttps && r.url.host !in LOOPBACK) {
            return ServerUrl.Result.Invalid(ServerUrl.Reason.HTTP_NOT_ALLOWED)
        }
        return r
    }

    private val LOOPBACK = setOf("127.0.0.1", "localhost")

    private fun recordLoginFailure() {
        guard.recordFailure()
        guardStore.update { guard.snapshot().map(Instant::toEpochMilli) }
    }

    /** Valida la URL y prueba usuario y contraseña con un descubrimiento completo (solo lectura). */
    suspend fun test(url: String, username: String, password: String): TestResult = withContext(Dispatchers.IO) {
        val server = when (val r = parseServer(url)) {
            is ServerUrl.Result.Invalid -> return@withContext TestResult.Invalid(r.reason)
            is ServerUrl.Result.Ok -> r.url
        }
        guard.waitBeforeNextAttempt()?.let { return@withContext TestResult.Blocked(it) }
        try {
            val discovery = CalDav(DavClient(server, Credentials(username, password))).discover()
            AppLog.event("test_ok")
            TestResult.Ok(discovery)
        } catch (e: DavException) {
            AppLog.event("test_failed", e)
            if (e is DavException.Unauthorized) recordLoginFailure()
            TestResult.Failed(e)
        }
    }

    /** Guarda la cuenta (y la contraseña, si se escribió una) con lo encontrado en [test]. */
    suspend fun saveAccount(existingId: Long?, url: String, username: String, password: String?, discovery: Discovery): Long =
        withContext(Dispatchers.IO) {
            val server = (parseServer(url) as ServerUrl.Result.Ok).url
            val id = Repo.saveCaldavAccount(existingId, server.toString(), username, discovery.principalUrl, discovery.homeUrl)
            if (password != null) credentials.save(id, password)
            Repo.replaceRemoteCalendars(id, discovery.calendars) { str(R.string.unnamed_calendar) }
            SyncScheduler.refreshSchedule()
            guard.reset()
            guardStore.update { emptyList() }
            id
        }

    /** Borra la cuenta, su caché y su contraseña del celu (en el servidor no cambia nada). */
    suspend fun deleteAccount(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            Repo.deleteAccount(id)
            credentials.delete(id)
            SyncScheduler.cancelAll()
        }
    }

    /** Resultado de una operación directa sobre el servidor (crear, renombrar o borrar un calendario). */
    sealed interface OpResult<out T> {
        data class Ok<T>(val value: T) : OpResult<T>
        data object NoAccount : OpResult<Nothing>
        data object NeedsPassword : OpResult<Nothing>
        data class Wait(val until: Instant) : OpResult<Nothing>
        data class Failed(val error: DavException) : OpResult<Nothing>
    }

    /**
     * Una operación sobre el servidor con las mismas reglas que la sincronización: nada si la cuenta
     * requiere contraseña o si el servidor pidió esperar; el primer 401 marca la cuenta y corta.
     */
    suspend fun <T> serverOp(block: (CalDav, homeUrl: String, accountId: Long) -> T): OpResult<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account = Repo.caldavAccount() ?: return@withLock OpResult.NoAccount
            if (account.state == AccountState.NEEDS_PASSWORD) return@withLock OpResult.NeedsPassword
            account.retryAt?.let { if (it > System.currentTimeMillis()) return@withLock OpResult.Wait(Instant.ofEpochMilli(it)) }
            val password = credentials.load(account.id)
            val server = (account.serverUrl?.let { parseServer(it) } as? ServerUrl.Result.Ok)?.url
            val home = account.homeUrl
            if (password == null || server == null || account.username == null || home == null) return@withLock OpResult.NeedsPassword
            try {
                OpResult.Ok(block(CalDav(DavClient(server, Credentials(account.username, password))), home, account.id))
            } catch (e: DavException) {
                AppLog.event("server_op_failed", e)
                if (e is DavException.Unauthorized) {
                    recordLoginFailure()
                    Repo.updateSyncState(account.id, AccountState.NEEDS_PASSWORD, e.code, null, 0)
                    OpResult.NeedsPassword
                } else {
                    OpResult.Failed(e)
                }
            }
        }
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account = Repo.caldavAccount() ?: return@withLock SyncResult.NoAccount
            if (account.state == AccountState.NEEDS_PASSWORD) return@withLock SyncResult.NeedsPassword
            val now = Instant.now()
            account.retryAt?.let { if (it > now.toEpochMilli()) return@withLock SyncResult.Wait(Instant.ofEpochMilli(it)) }
            val password = credentials.load(account.id)
            val server = (account.serverUrl?.let { parseServer(it) } as? ServerUrl.Result.Ok)?.url
            if (password == null || server == null || account.username == null) {
                Repo.updateSyncState(account.id, AccountState.NEEDS_PASSWORD, "MissingCredentials", null, 0)
                return@withLock SyncResult.NeedsPassword
            }
            val dav = DavClient(server, Credentials(account.username, password))
            val caldav = CalDav(dav)
            try {
                // Primero se sube lo pendiente, en orden. Un 401, 429/503, 5xx o error de red corta
                // acá con las mismas reglas que el resto de la sincronización.
                Pusher(dav).push(Repo.changeStore(account.id))
                val remote = account.homeUrl?.let(caldav::listCalendars) ?: caldav.discover().calendars
                Repo.replaceRemoteCalendars(account.id, remote) { str(R.string.unnamed_calendar) }
                val rows = Repo.calendars().filter { it.accountId == account.id }.associateBy { it.url }
                for (r in remote) {
                    val row = rows[r.url] ?: continue
                    // Mismo CTag que la última vez: nada cambió en ese calendario.
                    if (r.ctag != null && r.ctag == Repo.calendarCtag(row.id)) continue
                    syncEvents(caldav, row.id, r.url)
                    Repo.setCalendarTags(row.id, r.ctag, r.syncToken)
                }
                Repo.updateSyncState(account.id, AccountState.OK, null, null, 0, lastSync = System.currentTimeMillis())
                AppLog.event("sync_ok")
                SyncResult.Ok
            } catch (e: DavException) {
                AppLog.event("sync_failed", e)
                when (val decision = policy.decide(e, account.attempts)) {
                    RetryDecision.NeedsPassword -> {
                        recordLoginFailure()
                        Repo.updateSyncState(account.id, AccountState.NEEDS_PASSWORD, e.code, null, 0)
                        SyncResult.NeedsPassword
                    }
                    is RetryDecision.RetryIn -> {
                        val until = Instant.now().plus(decision.delay)
                        Repo.updateSyncState(account.id, AccountState.ERROR, e.code, until.toEpochMilli(), account.attempts + 1)
                        SyncResult.Wait(until)
                    }
                    is RetryDecision.GiveUp -> {
                        Repo.updateSyncState(account.id, AccountState.ERROR, e.code, null, 0)
                        SyncResult.Failed(e)
                    }
                }
            }
        }
    }

    /** Baja solo lo que cambió (por ETag) y borra lo que ya no está en el servidor. */
    private fun syncEvents(caldav: CalDav, calendarId: Long, calendarUrl: String) {
        val remote = caldav.listEtags(calendarUrl)
        val local = Repo.eventEtags(calendarId)
        val changed = remote.filter { (url, etag) -> etag == null || local[url] != etag }.keys
        val deleted = local.keys - remote.keys
        val fetched = if (changed.isEmpty()) emptyList() else caldav.multiget(calendarUrl, changed)
        val pending = Repo.pendingUids(calendarId)
        val upserts = fetched.mapNotNull { r ->
            val ics = r.ics ?: return@mapNotNull null
            // Un archivo que no se puede leer se salta (sin cortar el resto de la sincronización).
            val uid = Ics.uid(ics) ?: return@mapNotNull null
            // Un evento con cambios locales sin subir no se pisa con la versión del servidor.
            if (uid in pending) return@mapNotNull null
            Repo.StoredEvent(r.url, uid, r.etag, ics)
        }
        Repo.applyEvents(calendarId, upserts, deleted)
    }
}
