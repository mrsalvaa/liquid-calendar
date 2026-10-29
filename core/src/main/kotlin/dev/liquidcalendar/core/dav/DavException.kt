package dev.liquidcalendar.core.dav

import java.time.Duration

/**
 * Fallos al hablar con el servidor. Los mensajes nunca incluyen URLs, usuarios, cabeceras ni
 * cuerpos: se pueden mostrar o registrar sin filtrar nada.
 */
sealed class DavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401. Nunca se reintenta: el servidor puede bloquear la IP tras varios intentos fallidos. */
    class Unauthorized : DavException("401 Unauthorized")

    class Forbidden : DavException("403 Forbidden")
    class NotFound : DavException("404 Not Found")

    /** 412: el recurso cambió en el servidor (ETag distinto). */
    class PreconditionFailed : DavException("412 Precondition Failed")

    /** 429 o 503: hay que esperar [retryAfter] (si el servidor lo indicó) antes de volver a intentar. */
    class RetryLater(val status: Int, val retryAfter: Duration?) : DavException("$status, reintentar más tarde")

    /** Otros 5xx. */
    class ServerError(val status: Int) : DavException("$status Server Error")

    /** Otras respuestas inesperadas (4xx, 3xx sin Location...). */
    class UnexpectedStatus(val status: Int) : DavException("HTTP $status")

    /** Redirección a otro host, puerto o esquema (p. ej. de https a http): no se sigue. */
    class RedirectRefused : DavException("Redirección a otro origen rechazada")

    class TooManyRedirects : DavException("Demasiadas redirecciones")

    /**
     * Sin conexión, DNS, tiempo de espera... [kind] es solo el tipo de error. La causa original se
     * conserva para depurar, pero su mensaje puede incluir el host: nunca se escribe en un log.
     */
    class Network(val kind: String, cause: Throwable? = null) : DavException("Error de red ($kind)", cause)

    /** Certificado inválido o conexión TLS rechazada. */
    class Tls(val kind: String, cause: Throwable? = null) : DavException("Error de TLS ($kind)", cause)

    class TooLarge : DavException("Respuesta demasiado grande")

    /** XML o datos que no se pueden interpretar. */
    class BadResponse(reason: String) : DavException("Respuesta inválida: $reason")
}
