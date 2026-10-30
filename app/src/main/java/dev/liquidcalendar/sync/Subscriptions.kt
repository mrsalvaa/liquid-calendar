package dev.liquidcalendar.sync

import dev.liquidcalendar.BuildConfig
import dev.liquidcalendar.core.dav.DavException
import dev.liquidcalendar.core.feed.FeedClient
import dev.liquidcalendar.core.feed.FeedResult
import dev.liquidcalendar.core.feed.FeedUrl
import dev.liquidcalendar.core.ics.IcsException
import dev.liquidcalendar.core.ics.IcsFiles
import dev.liquidcalendar.core.sync.RetryDecision
import dev.liquidcalendar.core.sync.RetryPolicy
import dev.liquidcalendar.data.Account
import dev.liquidcalendar.data.AccountState
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Suscripciones webcal/ICS: viven solo en el dispositivo, son de solo lectura y se actualizan
 * desde el servidor del feed (que así ve la IP del dispositivo). Respetan el intervalo elegido,
 * ETag/Last-Modified, Retry-After y una espera exponencial ante errores. Los registros nunca
 * llevan la URL del feed.
 */
object Subscriptions {
    /** Opciones de intervalo en minutos; 0 = solo a mano. El mínimo es 1 hora. */
    val INTERVALS = listOf(60, 360, 720, 1440, 10080, 0)
    const val DEFAULT_INTERVAL = 720

    private val client = FeedClient()
    private val policy = RetryPolicy(base = Duration.ofMinutes(15), max = Duration.ofHours(24))
    private val mutex = Mutex()

    /**
     * Solo https (webcal:// cuenta como https). Las compilaciones de prueba, nunca la release,
     * aceptan además http hacia el propio celu, para un feed local reenviado con adb reverse.
     */
    fun parse(input: String): FeedUrl.Result {
        val r = FeedUrl.parse(input, allowHttp = BuildConfig.LOCALHOST_HTTP)
        if (r is FeedUrl.Result.Ok && r.url.toString().startsWith("http://") && r.url.host !in LOOPBACK) {
            return FeedUrl.Result.Invalid(FeedUrl.Reason.HTTP_NOT_ALLOWED)
        }
        return r
    }

    private val LOOPBACK = setOf("127.0.0.1", "localhost")

    sealed interface Result {
        data class Ok(val events: Int) : Result
        data object Unchanged : Result

        /** El feed redirige a otro servidor: se muestra y se pide confirmación. */
        data class Redirect(val to: FeedUrl) : Result
        data class Wait(val until: Instant) : Result
        data class Failed(val error: DavException?) : Result
    }

    /**
     * Agrega una suscripción con una sola descarga: si el feed falla o redirige a otro servidor,
     * no se guarda nada y se devuelve el motivo.
     */
    suspend fun subscribe(feed: FeedUrl, name: String, color: Int, refreshMinutes: Int, acceptedHosts: Set<String>): Result = withContext(Dispatchers.IO) {
        try {
            when (val r = client.fetch(feed, acceptedHosts = acceptedHosts)) {
                is FeedResult.Updated -> {
                    val events = IcsFiles.preview(r.ics).events
                    val calendarId = Repo.addSubscription(feed.toString(), name, color, refreshMinutes, acceptedHosts)
                    val accountId = Repo.calendars().first { it.id == calendarId }.accountId
                    Repo.replaceEvents(calendarId, events.map { Repo.StoredEvent(null, it.uid, null, it.ics) })
                    Repo.feedRefreshed(accountId, r.etag, r.lastModified)
                    Repo.updateSyncState(accountId, AccountState.OK, null, null, 0, lastSync = System.currentTimeMillis())
                    AppLog.event("feed_added")
                    Result.Ok(events.size)
                }
                FeedResult.NotModified -> Result.Failed(null)
                is FeedResult.NeedsConfirmation -> Result.Redirect(r.to)
            }
        } catch (e: DavException) {
            AppLog.event("feed_add_failed", e)
            Result.Failed(e)
        } catch (e: IcsException) {
            Result.Failed(null)
        }
    }

    /** Actualiza las que corresponde (o todas si [force]). */
    suspend fun refreshDue(force: Boolean = false) = withContext(Dispatchers.IO) {
        Repo.subscriptions().forEach { refresh(it, force) }
    }

    suspend fun refresh(account: Account, force: Boolean): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            account.retryAt?.let { if (it > now) return@withLock Result.Wait(Instant.ofEpochMilli(it)) }
            val interval = account.refreshMinutes ?: DEFAULT_INTERVAL
            if (!force && (interval <= 0 || (account.lastSync ?: 0) + interval * 60_000L > now)) return@withLock Result.Unchanged
            val feed = (account.serverUrl?.let { parse(it) } as? FeedUrl.Result.Ok)?.url ?: return@withLock Result.Failed(null)
            val calendar = Repo.calendarOf(account.id) ?: return@withLock Result.Failed(null)
            try {
                when (val r = client.fetch(feed, account.feedEtag, account.feedLastModified, account.acceptedHosts)) {
                    is FeedResult.Updated -> {
                        val events = IcsFiles.preview(r.ics).events
                        Repo.replaceEvents(calendar.id, events.map { Repo.StoredEvent(null, it.uid, null, it.ics) })
                        Repo.feedRefreshed(account.id, r.etag, r.lastModified)
                        Repo.updateSyncState(account.id, AccountState.OK, null, null, 0, lastSync = now)
                        AppLog.event("feed_ok")
                        Result.Ok(events.size)
                    }
                    FeedResult.NotModified -> {
                        Repo.updateSyncState(account.id, AccountState.OK, null, null, 0, lastSync = now)
                        Result.Unchanged
                    }
                    is FeedResult.NeedsConfirmation -> {
                        Repo.updateSyncState(account.id, AccountState.ERROR, "Redirect", null, 0)
                        Result.Redirect(r.to)
                    }
                }
            } catch (e: DavException) {
                AppLog.event("feed_failed", e)
                // Sin ráfagas: espera exponencial para red y 5xx, Retry-After si lo hay; el resto
                // (403, 404, 401: el feed pide credenciales) espera al próximo intervalo.
                val until = when (val d = policy.decide(e, account.attempts)) {
                    is RetryDecision.RetryIn -> now + d.delay.toMillis()
                    else -> null
                }
                Repo.updateSyncState(account.id, AccountState.ERROR, e.code, until, account.attempts + 1)
                Result.Failed(e)
            } catch (e: IcsException) {
                Repo.updateSyncState(account.id, AccountState.ERROR, "BadResponse", null, account.attempts + 1)
                Result.Failed(null)
            }
        }
    }
}
