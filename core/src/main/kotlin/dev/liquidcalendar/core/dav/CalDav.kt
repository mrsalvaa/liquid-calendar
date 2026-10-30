package dev.liquidcalendar.core.dav

import okhttp3.HttpUrl

/** Calendario del servidor tal como lo describe un PROPFIND. */
data class RemoteCalendar(
    /** URL absoluta de la colección (termina en "/"). */
    val url: String,
    val name: String?,
    /** Color en ARGB, o null si el servidor no tiene uno. */
    val color: Int?,
    val ctag: String?,
    val syncToken: String?,
    val readOnly: Boolean,
    /** Componentes admitidos (VEVENT, VTODO...); vacío si el servidor no lo dice (= todos). */
    val components: Set<String>,
) {
    val supportsEvents: Boolean get() = components.isEmpty() || "VEVENT" in components
}

data class Discovery(val principalUrl: String, val homeUrl: String, val calendars: List<RemoteCalendar>)

/** Un evento del servidor: dónde está, su versión (ETag) y su contenido iCalendar. */
data class RemoteResource(val url: String, val etag: String?, val ics: String?)

/** Operaciones CalDAV sobre un [DavClient]. */
class CalDav(private val dav: DavClient) {

    /**
     * Encuentra el principal del usuario, su calendar-home-set y los calendarios con eventos.
     * Empieza por la URL configurada y, si ahí no hay principal, prueba /.well-known/caldav.
     */
    fun discover(): Discovery {
        val base = dav.url(dav.server.toString())
        val principal = findPrincipal(base)
            ?: findPrincipal(dav.url("/.well-known/caldav"))
            ?: throw DavException.BadResponse("sin current-user-principal")
        val home = dav.propfind(principal, 0, listOf(CALENDAR_HOME_SET)).firstNotNullOfOrNull { r ->
            r[CALENDAR_HOME_SET]?.child(DavClient.HREF)?.text
        } ?: throw DavException.BadResponse("sin calendar-home-set")
        val homeUrl = dav.url(home)
        return Discovery(principal.toString(), homeUrl.toString(), listCalendars(homeUrl.toString()))
    }

    private fun findPrincipal(url: HttpUrl): HttpUrl? = try {
        dav.propfind(url, 0, listOf(CURRENT_USER_PRINCIPAL)).firstNotNullOfOrNull { r ->
            r[CURRENT_USER_PRINCIPAL]?.child(DavClient.HREF)?.text
        }?.let { dav.url(it) }
    } catch (e: DavException.NotFound) {
        null
    } catch (e: DavException.UnexpectedStatus) {
        // 405 u otros: esta URL no es un recurso WebDAV.
        null
    }

    /** Calendarios (con eventos) dentro del calendar-home-set. */
    fun listCalendars(homeUrl: String): List<RemoteCalendar> =
        dav.propfind(dav.url(homeUrl), 1, CALENDAR_PROPS)
            .filter { it[RESOURCETYPE]?.has(CALENDAR) == true }
            .map { r ->
                RemoteCalendar(
                    url = dav.url(r.href).toString(),
                    name = r[DISPLAYNAME]?.text?.takeIf { it.isNotBlank() },
                    color = r[CALENDAR_COLOR]?.text?.let(::parseColor),
                    ctag = r[GETCTAG]?.text?.takeIf { it.isNotEmpty() },
                    syncToken = r[SYNC_TOKEN]?.text?.takeIf { it.isNotEmpty() },
                    readOnly = r[CURRENT_USER_PRIVILEGE_SET]?.let { !canWrite(it) } ?: false,
                    components = r[SUPPORTED_COMPONENTS]?.all(COMP)?.mapNotNull { comp -> comp.attrName() }?.toSet().orEmpty(),
                )
            }
            .filter { it.supportsEvents }

    /** ETag de cada evento del calendario (sin el contenido). */
    fun listEtags(calendarUrl: String): Map<String, String?> {
        val body = DavClient.xmlRequest("calendar-query", Ns.CALDAV) {
            append("<d:prop><d:getetag/></d:prop>")
            append("<c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VEVENT\"/></c:comp-filter></c:filter>")
        }
        return dav.report(dav.url(calendarUrl), 1, body)
            .filter { !it.href.endsWith("/") }
            .associate { dav.url(it.href).toString() to it[GETETAG]?.text }
    }

    /** Contenido de los eventos pedidos, en tandas de [batch] para no recibir respuestas enormes. */
    fun multiget(calendarUrl: String, urls: Collection<String>, batch: Int = 50): List<RemoteResource> =
        urls.chunked(batch).flatMap { chunk ->
            val body = DavClient.xmlRequest("calendar-multiget", Ns.CALDAV) {
                append("<d:prop><d:getetag/><c:calendar-data/></d:prop>")
                chunk.forEach { append("<d:href>").append(xmlEscape(dav.url(it).encodedPath)).append("</d:href>") }
            }
            dav.report(dav.url(calendarUrl), 1, body).map { r ->
                RemoteResource(dav.url(r.href).toString(), r[GETETAG]?.text, r[CALENDAR_DATA]?.text)
            }
        }

    // --- Calendarios en el servidor ---

    /**
     * Crea un calendario dentro del calendar-home-set y devuelve su URL (el href es su identidad;
     * el nombre puede cambiar). [components]: "VEVENT" y, si se quiere, "VTODO".
     */
    fun mkcalendar(homeUrl: String, slug: String, name: String, color: Int, components: Set<String>): String {
        require(slug.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "slug inválido" }
        val url = dav.url(homeUrl).resolve("$slug/") ?: throw DavException.BadResponse("url")
        val body = DavClient.xmlRequest("mkcalendar", Ns.CALDAV) {
            append("<d:set><d:prop>")
            append("<d:displayname>").append(xmlEscape(name)).append("</d:displayname>")
            append("<i:calendar-color>").append(colorHex(color)).append("</i:calendar-color>")
            append("<c:supported-calendar-component-set>")
            components.forEach { append("<c:comp name=\"").append(xmlEscape(it)).append("\"/>") }
            append("</c:supported-calendar-component-set>")
            append("</d:prop></d:set>")
        }
        try {
            dav.send("MKCALENDAR", url, body, followRedirects = false)
        } catch (e: DavException.UnexpectedStatus) {
            if (e.status in setOf(405, 415, 501)) throw DavException.MkcalendarUnsupported(e.status) else throw e
        } catch (e: DavException.Forbidden) {
            throw DavException.MkcalendarUnsupported(403)
        } catch (e: DavException.ServerError) {
            // 501 Not Implemented: el servidor no conoce MKCALENDAR.
            if (e.status == 501) throw DavException.MkcalendarUnsupported(501) else throw e
        }
        return url.toString()
    }

    /** Cambia nombre y/o color. Lanza si el servidor rechaza alguna de las propiedades. */
    fun proppatch(calendarUrl: String, name: String?, color: Int?) {
        require(name != null || color != null)
        val body = DavClient.xmlRequest("propertyupdate", Ns.DAV) {
            append("<d:set><d:prop>")
            if (name != null) append("<d:displayname>").append(xmlEscape(name)).append("</d:displayname>")
            if (color != null) append("<i:calendar-color>").append(colorHex(color)).append("</i:calendar-color>")
            append("</d:prop></d:set>")
        }
        val result = dav.proppatch(dav.url(calendarUrl), body)
        if (result.any { (it.status ?: 200) !in 200..299 }) throw DavException.UnexpectedStatus(409)
    }

    /** Borra el calendario y todos sus eventos del servidor. Irreversible: solo por acción explícita. */
    fun deleteCalendar(calendarUrl: String) {
        dav.deleteCollection(dav.url(calendarUrl))
    }

    companion object {
        /** ARGB → "#RRGGBBFF" (el formato de Apple que usan Radicale y la mayoría de los clientes). */
        fun colorHex(argb: Int): String = "#%06X%02X".format(argb and 0xFFFFFF, (argb ushr 24) and 0xFF)

        val CURRENT_USER_PRINCIPAL = QName(Ns.DAV, "current-user-principal")
        val CALENDAR_HOME_SET = QName(Ns.CALDAV, "calendar-home-set")
        val RESOURCETYPE = QName(Ns.DAV, "resourcetype")
        val CALENDAR = QName(Ns.CALDAV, "calendar")
        val DISPLAYNAME = QName(Ns.DAV, "displayname")
        val CALENDAR_COLOR = QName(Ns.APPLE_ICAL, "calendar-color")
        val GETCTAG = QName(Ns.CALSERVER, "getctag")
        val SYNC_TOKEN = QName(Ns.DAV, "sync-token")
        val GETETAG = QName(Ns.DAV, "getetag")
        val CALENDAR_DATA = QName(Ns.CALDAV, "calendar-data")
        val SUPPORTED_COMPONENTS = QName(Ns.CALDAV, "supported-calendar-component-set")
        val COMP = QName(Ns.CALDAV, "comp")
        val CURRENT_USER_PRIVILEGE_SET = QName(Ns.DAV, "current-user-privilege-set")
        private val PRIVILEGE = QName(Ns.DAV, "privilege")

        private val CALENDAR_PROPS = listOf(
            RESOURCETYPE, DISPLAYNAME, CALENDAR_COLOR, GETCTAG, SYNC_TOKEN, SUPPORTED_COMPONENTS, CURRENT_USER_PRIVILEGE_SET,
        )

        private fun canWrite(privileges: XmlElement): Boolean =
            privileges.all(PRIVILEGE).flatMap { it.children }.any { it.name.ns == Ns.DAV && it.name.name in WRITE_PRIVILEGES }

        private val WRITE_PRIVILEGES = setOf("all", "write", "write-content", "bind")

        /** "#RRGGBB" o "#RRGGBBAA" (formato de Apple) → ARGB. */
        fun parseColor(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.length != 6 && hex.length != 8 || hex.any { it.digitToIntOrNull(16) == null }) return null
            val rgb = hex.substring(0, 6).toLong(16)
            val alpha = if (hex.length == 8) hex.substring(6, 8).toLong(16) else 0xFF
            // Un color transparente no se vería: se usa opaco.
            return ((if (alpha == 0L) 0xFF else alpha) shl 24 or rgb).toInt()
        }
    }
}

/** El atributo name de <comp name="VEVENT"/>. */
private fun XmlElement.attrName(): String? = attributes["name"]?.uppercase()
