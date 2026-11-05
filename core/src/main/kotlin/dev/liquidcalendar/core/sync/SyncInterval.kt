package dev.liquidcalendar.core.sync

/** Cada cuánto se sincroniza en segundo plano, en minutos. */
object SyncInterval {
    val OPTIONS = listOf(5, 15, 30, 60)
    const val DEFAULT = 60

    /** Android no programa trabajos periódicos de menos de 15 minutos. */
    const val PERIODIC_MINIMUM = 15

    /**
     * El valor guardado llevado a una de las opciones: versiones anteriores permitían "solo a mano"
     * (0, que pasa al valor por defecto) y hasta 12 horas (que pasan al máximo).
     */
    fun normalize(minutes: Int): Int =
        if (minutes <= 0) DEFAULT else OPTIONS.firstOrNull { it >= minutes } ?: OPTIONS.last()

    /** El período del trabajo periódico, que nunca baja del mínimo de Android. */
    fun periodicMinutes(minutes: Int): Int = normalize(minutes).coerceAtLeast(PERIODIC_MINIMUM)

    /** Por debajo del mínimo, cada turno deja programado el siguiente. */
    fun needsChain(minutes: Int): Boolean = normalize(minutes) < PERIODIC_MINIMUM
}
