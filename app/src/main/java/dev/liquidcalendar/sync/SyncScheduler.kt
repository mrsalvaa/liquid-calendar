package dev.liquidcalendar.sync

import android.annotation.SuppressLint
import android.content.Context
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.data.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Sincronización en segundo plano con WorkManager. Cada trabajo tiene un nombre único (no se
 * duplican) y todos pasan por [SyncEngine.sync], que no deja correr dos a la vez y aplica las
 * mismas reglas que el botón: con la cuenta en "requiere contraseña" no hace ninguna petición, y
 * un Retry-After o una espera exponencial se respetan reprogramando para esa hora.
 */
@SuppressLint("StaticFieldLeak") // Solo guarda el contexto de la aplicación, que vive lo mismo que el proceso.
object SyncScheduler {
    /** Opciones del ajuste, en minutos. 0 = solo a mano. El mínimo es 30. */
    val INTERVALS = listOf(30, 60, 180, 360, 720, 0)
    const val DEFAULT_INTERVAL = 60

    private const val PERIODIC = "sync-periodic"
    private const val SOON = "sync-soon"
    private const val RETRY = "sync-retry"

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** (Re)programa la sincronización periódica; 0 la apaga. Sin cuenta CalDAV no programa nada. */
    fun schedulePeriodic(minutes: Int, hasAccount: Boolean, hasSubscriptions: Boolean = false) {
        val wm = WorkManager.getInstance(app)
        // Con suscripciones hace falta un turno aunque la cuenta CalDAV esté "solo a mano".
        @Suppress("NAME_SHADOWING")
        val minutes = if ((minutes <= 0 || !hasAccount) && hasSubscriptions) 60 else minutes
        if (minutes <= 0 || (!hasAccount && !hasSubscriptions)) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(30).toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Reprograma según lo que haya: cuenta CalDAV, suscripciones y el intervalo de los ajustes. */
    fun refreshSchedule() {
        CalendarApp.scope.launch(Dispatchers.IO) {
            schedulePeriodic(Stores.settings.value.syncIntervalMinutes, Repo.caldavAccount() != null, Repo.subscriptions().isNotEmpty())
        }
    }

    /** Subir pronto un cambio local (unos segundos de margen para agrupar varios cambios seguidos). */
    fun pushSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    /** Reintentar a la hora que pidió el servidor (o la de la espera exponencial). */
    fun retryAt(at: Instant) {
        val delay = Duration.between(Instant.now(), at).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    /** Sin cuenta (o al quitarla): no queda nada programado. */
    fun cancelAll() {
        WorkManager.getInstance(app).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(SOON)
            cancelUniqueWork(RETRY)
        }
    }
}

/** Un intento de sincronización. Nunca pide reintentos a WorkManager: los decide SyncEngine. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        when (val r = SyncEngine.sync()) {
            is SyncResult.Wait -> SyncScheduler.retryAt(r.until)
            else -> Unit // Ok, sin cuenta, requiere contraseña o error permanente: esperar al próximo turno.
        }
        // Las suscripciones van por su cuenta: no dependen de la cuenta CalDAV ni la frenan.
        Subscriptions.refreshDue()
        return Result.success()
    }
}
