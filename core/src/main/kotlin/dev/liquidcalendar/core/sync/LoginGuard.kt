package dev.liquidcalendar.core.sync

import java.time.Duration
import java.time.Instant

/**
 * Freno local para los intentos con contraseña incorrecta (por ejemplo, al tocar varias veces
 * "Probar conexión"). Hay servidores que bloquean la IP tras 5 respuestas 401 en 10 minutos: aquí
 * se corta antes, con [limit] fallos dentro de [window].
 */
class LoginGuard(
    private val limit: Int = 3,
    private val window: Duration = Duration.ofMinutes(10),
) {
    private val failures = ArrayDeque<Instant>()

    @Synchronized
    fun recordFailure(now: Instant = Instant.now()) {
        prune(now)
        failures.addLast(now)
    }

    /** null si se puede intentar; si no, cuánto falta para poder hacerlo. */
    @Synchronized
    fun waitBeforeNextAttempt(now: Instant = Instant.now()): Duration? {
        prune(now)
        if (failures.size < limit) return null
        return Duration.between(now, failures.first().plus(window)).coerceAtLeast(Duration.ofSeconds(1))
    }

    @Synchronized
    fun reset() = failures.clear()

    /** Instantes de los fallos recientes, para guardarlos entre reinicios de la app. */
    @Synchronized
    fun snapshot(now: Instant = Instant.now()): List<Instant> {
        prune(now)
        return failures.toList()
    }

    @Synchronized
    fun restore(instants: List<Instant>, now: Instant = Instant.now()) {
        failures.clear()
        instants.sorted().forEach { failures.addLast(it) }
        prune(now)
    }

    private fun prune(now: Instant) {
        while (failures.isNotEmpty() && Duration.between(failures.first(), now) >= window) failures.removeFirst()
    }
}
