package dev.liquidcalendar.util

import android.util.Log
import dev.liquidcalendar.core.dav.DavException

/**
 * Único punto de registro de la app. Solo acepta un nombre de evento fijo y, opcionalmente, una
 * [DavException], cuyo mensaje nunca lleva URLs, usuarios, cabeceras ni contenido de calendarios.
 * No registra trazas: la causa original de un error de red puede incluir el host.
 */
object AppLog {
    private const val TAG = "LiquidCalendar"

    fun event(name: String, error: DavException? = null) {
        val text = if (error == null) name else "$name: ${error.code} (${error.message})"
        Log.i(TAG, text)
    }
}
