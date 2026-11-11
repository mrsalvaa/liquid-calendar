package dev.liquidcalendar.sync

import android.annotation.SuppressLint
import android.content.Context
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.core.sync.SyncInterval
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
import androidx.work.workDataOf
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
    private const val PERIODIC = "sync-periodic"
    private const val CHAIN = "sync-chain"
    private const val SOON = "sync-soon"
    private const val RETRY = "sync-retry"

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * (Re)programa la sincronización periódica. Sin cuenta CalDAV ni suscripciones no programa nada;
     * con solo suscripciones basta un turno por hora. Android no programa trabajos periódicos de
     * menos de 15 minutos: para 5, queda uno de 15 de respaldo y cada turno programa el siguiente.
     */
    fun schedulePeriodic(minutes: Int, hasAccount: Boolean, hasSubscriptions: Boolean = false) {
        val wm = WorkManager.getInstance(app)
        if (!hasAccount && !hasSubscriptions) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(CHAIN)
            return
        }
        val interval = if (hasAccount) SyncInterval.normalize(minutes) else SyncInterval.DEFAULT
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SyncInterval.periodicMinutes(interval).toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        if (SyncInterval.needsChain(interval)) enqueueChain(interval, ExistingWorkPolicy.KEEP) else wm.cancelUniqueWork(CHAIN)
    }

    private fun enqueueChain(minutes: Int, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(CHAIN, policy, request)
    }

    /**
     * El turno siguiente, contado desde el que acaba de terminar. Tiene que ser lo último que haga
     * el trabajo: si es el mismo de la cadena, reemplazarlo también lo da por terminado.
     */
    fun continueChain() {
        val minutes = Stores.settings.value.syncIntervalMinutes
        if (SyncInterval.needsChain(minutes)) enqueueChain(SyncInterval.normalize(minutes), ExistingWorkPolicy.REPLACE)
    }

    /** Reprograma según lo que haya: cuenta CalDAV, suscripciones y el intervalo de los ajustes. */
    fun refreshSchedule() {
        CalendarApp.scope.launch(Dispatchers.IO) {
            schedulePeriodic(Stores.settings.value.syncIntervalMinutes, Repo.caldavAccount() != null, Repo.subscriptions().isNotEmpty())
        }
    }

    /**
     * Subir pronto un cambio local (unos segundos de margen para agrupar varios cambios seguidos).
     * Prueba aunque esté corriendo la espera tras un error de red: si sigue sin conexión, queda en
     * la cola y sale con el próximo reintento.
     */
    fun pushSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.NOW to true))
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
            cancelUniqueWork(CHAIN)
            cancelUniqueWork(SOON)
            cancelUniqueWork(RETRY)
        }
    }
}

/** Un intento de sincronización. Nunca pide reintentos a WorkManager: los decide SyncEngine. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = SyncEngine.sync(now = inputData.getBoolean(NOW, false))
        // Si quedó una espera (del servidor o propia tras un error de red), el reintento va a esa hora.
        Repo.caldavAccount()?.retryAt?.takeIf { it > System.currentTimeMillis() }?.let { SyncScheduler.retryAt(Instant.ofEpochMilli(it)) }
        // Las suscripciones van por su cuenta: no dependen de la cuenta CalDAV ni la frenan.
        Subscriptions.refreshDue()
        // Sin cuenta o sin contraseña no hay nada que hacer hasta guardarlas, y eso reprograma; una
        // espera sigue desde su propio reintento.
        if (result is SyncResult.Ok || result is SyncResult.Failed) SyncScheduler.continueChain()
        return Result.success()
    }

    companion object {
        /** Un cambio local: probar ya, sin esperar el fin de la espera propia. */
        const val NOW = "now"
    }
}
