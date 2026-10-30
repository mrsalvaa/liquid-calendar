package dev.liquidcalendar.core.feed

import dev.liquidcalendar.core.dav.DavClient
import dev.liquidcalendar.core.dav.DavException
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Dirección de una suscripción (webcal/ICS). webcal:// y webcals:// se tratan como https://;
 * http:// y las credenciales dentro de la URL se rechazan. Los parámetros (?…) se permiten
 * porque muchos feeds llevan ahí su token, así que la URL nunca se escribe en un registro.
 */
class FeedUrl private constructor(val url: HttpUrl) {
    /** Lo único que se muestra o se puede registrar: el host. */
    val host: String get() = url.host

    /** Host (y puerto, si no es el estándar), para aceptar una redirección a este servidor. */
    val authority: String get() = FeedClient.authority(url)

    override fun toString(): String = url.toString()
    override fun equals(other: Any?) = other is FeedUrl && other.url == url
    override fun hashCode() = url.hashCode()

    enum class Reason { EMPTY, MALFORMED, HTTP_NOT_ALLOWED, UNSUPPORTED_SCHEME, CREDENTIALS_IN_URL }

    sealed interface Result {
        data class Ok(val url: FeedUrl) : Result
        data class Invalid(val reason: Reason) : Result
    }

    companion object {
        /** [allowHttp] solo para pruebas con un servidor local. */
        fun parse(input: String, allowHttp: Boolean = false): Result {
            val text = input.trim()
            if (text.isEmpty()) return Result.Invalid(Reason.EMPTY)
            val scheme = text.substringBefore("://", "").lowercase()
            val rest = text.substringAfter("://", text)
            val normalized = when (scheme) {
                "webcal", "webcals", "https" -> "https://$rest"
                "" -> "https://$text"
                "http" -> if (allowHttp) "http://$rest" else return Result.Invalid(Reason.HTTP_NOT_ALLOWED)
                else -> return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
            }
            val url = normalized.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Result.Invalid(Reason.CREDENTIALS_IN_URL)
            return Result.Ok(FeedUrl(url))
        }

        internal fun of(url: HttpUrl) = FeedUrl(url)
    }
}

/** Resultado de una descarga. */
sealed interface FeedResult {
    data class Updated(val ics: String, val etag: String?, val lastModified: String?) : FeedResult

    /** 304: el feed no cambió desde la última vez. */
    data object NotModified : FeedResult

    /** El feed redirige a otro host: no se sigue sin que el usuario lo confirme. */
    data class NeedsConfirmation(val to: FeedUrl) : FeedResult
}

/**
 * Descarga de suscripciones, sin nada que identifique al usuario: ni credenciales, ni cookies,
 * ni caché, y un User-Agent igual para todos. Tamaño y tiempo limitados. Los errores son las
 * mismas DavException que el cliente CalDAV (429/503 con Retry-After, 5xx, red...).
 */
class FeedClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val maxBytes: Long = MAX_BYTES,
) {
    fun fetch(feed: FeedUrl, etag: String? = null, lastModified: String? = null, acceptedHosts: Set<String> = emptySet()): FeedResult {
        var current = feed.url
        repeat(DavClient.MAX_REDIRECTS + 1) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", DavClient.USER_AGENT)
                .header("Accept", "text/calendar, */*;q=0.5")
                .apply {
                    if (etag != null) header("If-None-Match", etag)
                    if (lastModified != null) header("If-Modified-Since", lastModified)
                }
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: SSLException) {
                throw DavException.Tls(e.javaClass.simpleName, e)
            } catch (e: UnknownHostException) {
                throw DavException.Network("UnknownHost", e)
            } catch (e: SocketTimeoutException) {
                throw DavException.Network("Timeout", e)
            } catch (e: IOException) {
                throw DavException.Network(e.javaClass.simpleName, e)
            }
            response.use { r ->
                when (r.code) {
                    in 200..299 -> {
                        val body = r.body
                        if (body.contentLength() > maxBytes) throw DavException.TooLarge()
                        val source = body.source()
                        try {
                            if (source.request(maxBytes + 1)) throw DavException.TooLarge()
                        } catch (e: IOException) {
                            throw DavException.Network(e.javaClass.simpleName, e)
                        }
                        return FeedResult.Updated(source.buffer.readUtf8(), r.header("ETag"), r.header("Last-Modified"))
                    }
                    304 -> return FeedResult.NotModified
                    301, 302, 303, 307, 308 -> {
                        val to = r.header("Location")?.let { current.resolve(it) } ?: throw DavException.UnexpectedStatus(r.code)
                        when (redirect(current, to, acceptedHosts)) {
                            Redirect.FOLLOW -> current = to
                            Redirect.ASK -> return FeedResult.NeedsConfirmation(FeedUrl.of(to))
                            Redirect.REFUSE -> throw DavException.RedirectRefused()
                        }
                    }
                    else -> throw DavClient.statusError(r)
                }
            }
        }
        throw DavException.TooManyRedirects()
    }

    enum class Redirect { FOLLOW, ASK, REFUSE }

    companion object {
        /** 5 MB: un calendario de feriados de varios años pesa decenas de KB. */
        const val MAX_BYTES = 5L * 1024 * 1024

        /** "host" o "host:puerto" (si no es el puerto estándar): lo que identifica a otro servidor. */
        fun authority(url: HttpUrl): String =
            url.host.lowercase() + if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"

        /** Nunca de https a http; a otro servidor (host o puerto), solo si el usuario ya lo aceptó. */
        fun redirect(from: HttpUrl, to: HttpUrl, acceptedHosts: Set<String>): Redirect = when {
            from.scheme == "https" && to.scheme != "https" -> Redirect.REFUSE
            authority(to) == authority(from) -> Redirect.FOLLOW
            authority(to) in acceptedHosts.map { it.lowercase() } -> Redirect.FOLLOW
            else -> Redirect.ASK
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .build()
    }
}
