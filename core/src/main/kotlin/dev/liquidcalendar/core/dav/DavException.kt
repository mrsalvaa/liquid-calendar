package dev.liquidcalendar.core.dav

import java.time.Duration

/**
 * Fallos al hablar con el servidor. Los mensajes nunca incluyen URLs, usuarios, cabeceras ni
 * cuerpos: se pueden mostrar o registrar sin filtrar nada.
 */
sealed class DavException(
    /**
     * Identificador estable del tipo de error, para guardarlo o registrarlo. No se usa el nombre de
     * la clase porque R8 lo cambia en las compilaciones optimizadas.
     */
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 401. Nunca se reintenta: el servidor puede bloquear la IP tras varios intentos fallidos. */
    class Unauthorized : DavException("Unauthorized", "401 Unauthorized")

    class Forbidden : DavException("Forbidden", "403 Forbidden")
    class NotFound : DavException("NotFound", "404 Not Found")

    /** 412: el recurso cambió en el servidor (ETag distinto). */
    class PreconditionFailed : DavException("PreconditionFailed", "412 Precondition Failed")

    /** 429 o 503: hay que esperar [retryAfter] (si el servidor lo indicó) antes de volver a intentar. */
    class RetryLater(val status: Int, val retryAfter: Duration?) : DavException("RetryLater", "$status, reintentar más tarde")

    /** Otros 5xx. */
    class ServerError(val status: Int) : DavException("ServerError", "$status Server Error")

    /** Otras respuestas inesperadas (4xx, 3xx sin Location...). */
    class UnexpectedStatus(val status: Int) : DavException("UnexpectedStatus", "HTTP $status")

    /** Redirección a otro host, puerto o esquema (p. ej. de https a http): no se sigue. */
    class RedirectRefused : DavException("RedirectRefused", "Redirección a otro origen rechazada")

    class TooManyRedirects : DavException("TooManyRedirects", "Demasiadas redirecciones")

    /**
     * Sin conexión, DNS, tiempo de espera... [kind] es solo el tipo de error. La causa original se
     * conserva para depurar, pero su mensaje puede incluir el host: nunca se escribe en un log.
     */
    class Network(val kind: String, cause: Throwable? = null) : DavException("Network", "Error de red ($kind)", cause)

    /** Certificado inválido o conexión TLS rechazada. */
    class Tls(val kind: String, cause: Throwable? = null) : DavException("Tls", "Error de TLS ($kind)", cause)

    class TooLarge : DavException("TooLarge", "Respuesta demasiado grande")

    /** XML o datos que no se pueden interpretar. */
    class BadResponse(reason: String) : DavException("BadResponse", "Respuesta inválida: $reason")
}
