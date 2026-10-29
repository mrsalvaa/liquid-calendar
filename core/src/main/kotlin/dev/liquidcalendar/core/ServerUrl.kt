package dev.liquidcalendar.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * URL base de un servidor CalDAV, ya validada: esquema https (o http solo si se permitió
 * explícitamente), con host, sin credenciales ni parámetros y con la ruta terminada en "/".
 */
class ServerUrl private constructor(val uri: URI) {
    val isHttps: Boolean get() = uri.scheme == "https"
    val host: String get() = uri.host

    /** Puerto efectivo (el explícito o el predeterminado del esquema). */
    val port: Int get() = if (uri.port != -1) uri.port else if (isHttps) 443 else 80

    /** Resuelve una ruta o URL que devuelve el servidor (p. ej. un href) contra esta base. */
    fun resolve(href: String): URI = uri.resolve(href)

    override fun toString(): String = uri.toString()
    override fun equals(other: Any?): Boolean = other is ServerUrl && other.uri == uri
    override fun hashCode(): Int = uri.hashCode()

    sealed interface Result {
        data class Ok(val url: ServerUrl) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason {
        EMPTY,
        MALFORMED,
        UNSUPPORTED_SCHEME,
        /** http:// sin haber activado la opción de conexión sin cifrar. */
        HTTP_NOT_ALLOWED,
        MISSING_HOST,
        /** Usuario o contraseña dentro de la URL: van en sus propios campos. */
        CREDENTIALS_IN_URL,
        /** Parámetros (?…) o fragmento (#…), que un servidor CalDAV no necesita. */
        QUERY_OR_FRAGMENT,
        BAD_PORT,
    }

    companion object {
        /**
         * Valida lo que escribió el usuario. Sin esquema se asume https://. [allowHttp] solo debe
         * ser true si el usuario activó a propósito la conexión sin cifrar (p. ej. en su red local).
         */
        fun parse(input: String, allowHttp: Boolean = false): Result {
            val text = input.trim()
            if (text.isEmpty()) return Result.Invalid(Reason.EMPTY)
            val withScheme = if ("://" in text) text else "https://$text"
            val uri = try {
                URI(withScheme)
            } catch (e: URISyntaxException) {
                return Result.Invalid(Reason.MALFORMED)
            }
            when (uri.scheme?.lowercase()) {
                "https" -> Unit
                "http" -> if (!allowHttp) return Result.Invalid(Reason.HTTP_NOT_ALLOWED)
                else -> return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
            }
            if (uri.rawUserInfo != null) return Result.Invalid(Reason.CREDENTIALS_IN_URL)
            // Un puerto no numérico deja el host sin parsear: se distingue del host vacío.
            if (uri.host.isNullOrEmpty()) {
                return Result.Invalid(if (uri.rawAuthority?.contains(':') == true) Reason.BAD_PORT else Reason.MISSING_HOST)
            }
            if (uri.port != -1 && uri.port !in 1..65535) return Result.Invalid(Reason.BAD_PORT)
            if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid(Reason.QUERY_OR_FRAGMENT)
            val path = uri.rawPath.orEmpty().let { if (it.endsWith("/")) it else "$it/" }
            val normalized = URI(
                uri.scheme.lowercase(),
                null,
                uri.host.lowercase(),
                uri.port,
                null,
                null,
                null,
            ).toString() + path
            return Result.Ok(ServerUrl(URI(normalized)))
        }

        /**
         * Si el host parece de una red local (localhost, IP privada, .local, .lan, .home.arpa).
         * Solo literales: nunca resuelve nombres por DNS.
         */
        fun isLocalHost(host: String): Boolean {
            val h = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
            if (h == "localhost" || h.endsWith(".localhost")) return true
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            val literal = ipLiteral(h) ?: return false
            return literal.isLoopbackAddress || literal.isSiteLocalAddress || literal.isLinkLocalAddress ||
                (literal !is Inet4Address && (literal.address[0].toInt() and 0xFE) == 0xFC)
        }

        /** Convierte un literal IPv4/IPv6 sin hacer consultas DNS; null si es un nombre. */
        private fun ipLiteral(h: String): InetAddress? {
            val isV4 = h.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 } }
            val isV6 = ':' in h && h.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
            if (!isV4 && !isV6) return null
            return try {
                InetAddress.getByName(h)
            } catch (e: Exception) {
                null
            }
        }
    }
}
