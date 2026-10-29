package dev.liquidcalendar.core.dav

import dev.liquidcalendar.core.ServerUrl
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Usuario y contraseña. toString() no los muestra nunca. */
class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(***)"
}

/** Una respuesta de un multistatus: href y propiedades encontradas (solo las que vinieron con 2xx). */
class DavResponse(val href: String, val status: Int?, val props: Map<QName, XmlElement>) {
    operator fun get(q: QName): XmlElement? = props[q]
}

/**
 * Cliente WebDAV/CalDAV mínimo. Reglas de seguridad que no se pueden desactivar:
 * - La autenticación Basic va en cada petición desde la primera (sin el ida y vuelta 401 →
 *   reintento con credenciales, que el servidor contaría como intento fallido).
 * - Ante un 401 lanza [DavException.Unauthorized] y no reintenta nunca.
 * - Las redirecciones se siguen a mano y solo dentro del mismo origen (esquema, host y puerto):
 *   nunca a otro host ni de https a http, así que la contraseña no sale del servidor configurado.
 * - Respuestas con tamaño máximo; el XML se lee con [SafeXml].
 */
class DavClient(
    val server: ServerUrl,
    private val credentials: Credentials,
    private val http: OkHttpClient = defaultHttpClient(server.isHttps),
    private val maxBodyBytes: Long = SafeXml.MAX_BYTES.toLong(),
) {
    private val origin: HttpUrl = server.toString().toHttpUrl()
    private val authorization = okhttp3.Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8)

    fun url(href: String): HttpUrl = origin.resolve(href) ?: throw DavException.BadResponse("href inválido")

    /** PROPFIND con las propiedades pedidas. [depth] 0 o 1. */
    fun propfind(url: HttpUrl, depth: Int, props: List<QName>): List<DavResponse> {
        val body = xmlRequest("propfind", Ns.DAV) { append("<d:prop>").append(props.joinToString("") { it.emptyTag() }).append("</d:prop>") }
        return multistatus(send("PROPFIND", url, body, depth))
    }

    /** REPORT con el cuerpo XML dado (calendar-query, calendar-multiget, sync-collection...). */
    fun report(url: HttpUrl, depth: Int, body: String): List<DavResponse> = multistatus(send("REPORT", url, body, depth))

    /** Ejecuta una petición siguiendo solo redirecciones al mismo origen; devuelve el cuerpo. */
    fun send(method: String, url: HttpUrl, body: String?, depth: Int? = null): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            requireSameOrigin(current)
            val request = Request.Builder()
                .url(current)
                .method(method, body?.toRequestBody(XML))
                .header("Authorization", authorization)
                .header("User-Agent", USER_AGENT)
                .apply { if (depth != null) header("Depth", depth.toString()) }
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
                    in 200..299 -> return readBody(r)
                    301, 302, 307, 308 -> {
                        val location = r.header("Location") ?: throw DavException.UnexpectedStatus(r.code)
                        current = current.resolve(location) ?: throw DavException.RedirectRefused()
                        if (!isSameOrigin(current)) throw DavException.RedirectRefused()
                    }
                    else -> throw statusError(r)
                }
            }
        }
        throw DavException.TooManyRedirects()
    }

    private fun requireSameOrigin(url: HttpUrl) {
        // Nunca debería pasar (las redirecciones ya se filtran), pero la contraseña depende de esto.
        if (!isSameOrigin(url)) throw DavException.RedirectRefused()
    }

    private fun isSameOrigin(url: HttpUrl): Boolean = sameOrigin(origin, url)

    private fun readBody(r: Response): ByteArray {
        val body = r.body
        if (body.contentLength() > maxBodyBytes) throw DavException.TooLarge()
        val source = body.source()
        try {
            if (source.request(maxBodyBytes + 1)) throw DavException.TooLarge()
            return source.buffer.readByteArray()
        } catch (e: IOException) {
            throw DavException.Network(e.javaClass.simpleName, e)
        }
    }

    private fun multistatus(bytes: ByteArray): List<DavResponse> {
        val root = try {
            SafeXml.parse(bytes)
        } catch (e: XmlException) {
            throw DavException.BadResponse(e.message ?: "XML")
        }
        if (root.name != MULTISTATUS) throw DavException.BadResponse("no es multistatus")
        return root.all(RESPONSE).mapNotNull { response ->
            val href = response.child(HREF)?.text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val props = buildMap {
                for (propstat in response.all(PROPSTAT)) {
                    val code = statusCode(propstat.child(STATUS)?.text)
                    if (code == null || code !in 200..299) continue
                    propstat.child(PROP)?.children?.forEach { put(it.name, it) }
                }
            }
            DavResponse(href, statusCode(response.child(STATUS)?.text), props)
        }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "LiquidCalendar"
        private val XML = "application/xml; charset=utf-8".toMediaType()

        val MULTISTATUS = QName(Ns.DAV, "multistatus")
        val RESPONSE = QName(Ns.DAV, "response")
        val HREF = QName(Ns.DAV, "href")
        val PROPSTAT = QName(Ns.DAV, "propstat")
        val PROP = QName(Ns.DAV, "prop")
        val STATUS = QName(Ns.DAV, "status")

        /**
         * Cliente HTTP sin redirecciones automáticas, sin cookies ni caché y, para https, solo TLS
         * moderno. La verificación de certificados es la del sistema.
         *
         * retryOnConnectionFailure: OkHttp repite la petición solo si la conexión falla antes de
         * recibir respuesta (típicamente una conexión reutilizada que el servidor ya cerró por
         * inactividad). Un 401 es una respuesta, así que nunca se repite por esto.
         */
        fun defaultHttpClient(https: Boolean): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .connectionSpecs(listOf(if (https) ConnectionSpec.MODERN_TLS else ConnectionSpec.CLEARTEXT))
            .build()

        /** Mismo esquema, host y puerto: solo ahí se sigue una redirección y se manda la contraseña. */
        fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
            a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

        /** "HTTP/1.1 200 OK" → 200. */
        fun statusCode(line: String?): Int? = line?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull()

        internal fun statusError(r: Response): DavException = when (r.code) {
            401 -> DavException.Unauthorized()
            403 -> DavException.Forbidden()
            404 -> DavException.NotFound()
            412 -> DavException.PreconditionFailed()
            429, 503 -> DavException.RetryLater(r.code, parseRetryAfter(r.header("Retry-After"), Instant.now()))
            in 500..599 -> DavException.ServerError(r.code)
            else -> DavException.UnexpectedStatus(r.code)
        }

        /** Retry-After en segundos o como fecha HTTP; null si falta o no se entiende. Máximo 24 h. */
        fun parseRetryAfter(value: String?, now: Instant): Duration? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val d = v.toLongOrNull()?.let { Duration.ofSeconds(it) }
                ?: try {
                    Duration.between(now, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                } catch (e: Exception) {
                    return null
                }
            return d.coerceIn(Duration.ZERO, Duration.ofHours(24))
        }

        private fun QName.emptyTag(): String = when (ns) {
            Ns.DAV -> "<d:$name/>"
            Ns.CALDAV -> "<c:$name/>"
            Ns.CALSERVER -> "<cs:$name/>"
            Ns.APPLE_ICAL -> "<i:$name/>"
            else -> "<x:$name xmlns:x=\"${xmlEscape(ns)}\"/>"
        }

        /** Documento XML con los prefijos habituales declarados en la raíz. */
        fun xmlRequest(root: String, rootNs: String, content: StringBuilder.() -> Unit): String = buildString {
            val prefix = if (rootNs == Ns.CALDAV) "c" else "d"
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<$prefix:$root xmlns:d=\"DAV:\" xmlns:c=\"${Ns.CALDAV}\" xmlns:cs=\"${Ns.CALSERVER}\" xmlns:i=\"${Ns.APPLE_ICAL}\">")
            content()
            append("</$prefix:$root>")
        }
    }
}
