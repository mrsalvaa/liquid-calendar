package dev.liquidcalendar.core.sync

import dev.liquidcalendar.core.dav.DavException
import java.time.Duration
import kotlin.random.Random

/** Qué hacer después de un error de sincronización. */
sealed interface RetryDecision {
    /**
     * 401: la cuenta queda marcada como "requiere volver a ingresar la contraseña" y no se vuelve a
     * intentar (ni manual ni en segundo plano) hasta que el usuario la edite. Algunos servidores
     * bloquean la IP tras unos pocos 401.
     */
    data object NeedsPassword : RetryDecision

    /** Reintentar dentro de [delay] (Retry-After del servidor o espera exponencial). */
    data class RetryIn(val delay: Duration) : RetryDecision

    /** No reintentar automáticamente: el próximo intento es la sincronización periódica o manual. */
    data class GiveUp(val error: DavException) : RetryDecision
}

/**
 * Decide los reintentos. La espera exponencial es solo para errores de red y 5xx; ante 429 o 503
 * manda el Retry-After del servidor; un 401 nunca se reintenta.
 */
class RetryPolicy(
    private val base: Duration = Duration.ofSeconds(30),
    private val max: Duration = Duration.ofHours(6),
    private val random: Random = Random.Default,
) {
    /** [attempt] es el número de reintentos ya hechos (0 tras el primer fallo). */
    fun decide(error: DavException, attempt: Int): RetryDecision = when (error) {
        is DavException.Unauthorized -> RetryDecision.NeedsPassword
        is DavException.RetryLater -> RetryDecision.RetryIn(error.retryAfter ?: backoff(attempt))
        is DavException.ServerError, is DavException.Network -> RetryDecision.RetryIn(backoff(attempt))
        else -> RetryDecision.GiveUp(error)
    }

    /** base · 2^attempt con ±20 % de variación, hasta [max]. */
    fun backoff(attempt: Int): Duration {
        val exp = base.toMillis().toDouble() * Math.pow(2.0, attempt.coerceIn(0, 30).toDouble())
        val capped = minOf(exp, max.toMillis().toDouble())
        val jitter = 1.0 + (random.nextDouble() * 0.4 - 0.2)
        return Duration.ofMillis((capped * jitter).toLong().coerceAtMost(max.toMillis()))
    }
}
