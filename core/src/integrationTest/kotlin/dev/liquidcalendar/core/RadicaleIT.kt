package dev.liquidcalendar.core

import dev.liquidcalendar.core.dav.CalDav
import dev.liquidcalendar.core.dav.Credentials
import dev.liquidcalendar.core.dav.DavClient
import dev.liquidcalendar.core.dav.DavException
import dev.liquidcalendar.core.ics.DraftTime
import dev.liquidcalendar.core.ics.EventDraft
import dev.liquidcalendar.core.ics.Ics
import dev.liquidcalendar.core.ics.IcsWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contra el Radicale local de scripts/radicale-test.sh (o docker-compose.test.yml). Nunca contra
 * un servidor real: la URL por defecto es 127.0.0.1 y las credenciales son las de prueba.
 */
class RadicaleIT {
    private val baseUrl = System.getenv("RADICALE_URL") ?: "http://127.0.0.1:5232/"
    private val user = System.getenv("RADICALE_TEST_USER") ?: "test"
    private val password = System.getenv("RADICALE_TEST_PASSWORD") ?: "local-test-pass" // secret-check: ignore
    private val server = (ServerUrl.parse(baseUrl, allowHttp = true) as ServerUrl.Result.Ok).url
    private val raw = OkHttpClient()
    private val slug = "it-" + UUID.randomUUID().toString().take(8)
    private val calendarUrl = "${baseUrl}$user/$slug/"
    private val uid = "${UUID.randomUUID()}@liquid-calendar-test"

    init {
        require(ServerUrl.isLocalHost(server.host)) { "Las pruebas de integración solo corren contra un servidor local" }
    }

    private fun rawRequest(method: String, url: String, body: String?, type: String = "application/xml") {
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody(type.toMediaType()))
            .header("Authorization", okhttp3.Credentials.basic(user, password))
            .build()
        raw.newCall(request).execute().use { check(it.isSuccessful) { "$method → ${it.code}" } }
    }

    @BeforeTest
    fun createCalendarWithAnEvent() {
        rawRequest(
            "MKCALENDAR",
            calendarUrl,
            """<?xml version="1.0"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:I="http://apple.com/ns/ical/">
               <D:set><D:prop><D:displayname>Prueba ñ</D:displayname><I:calendar-color>#FF7A8AFF</I:calendar-color>
               <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>""",
        )
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Liquid Calendar//IT//ES",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261029T120000Z", "DTSTART:20261030T150000Z",
            "DTEND:20261030T160000Z", "SUMMARY:Evento de prueba", "END:VEVENT", "END:VCALENDAR", "",
        ).joinToString("\r\n")
        rawRequest("PUT", "${calendarUrl}event.ics", ics, "text/calendar; charset=utf-8")
    }

    @AfterTest
    fun deleteCalendar() {
        rawRequest("DELETE", calendarUrl, null)
    }

    private fun calDav(pass: String = password) = CalDav(DavClient(server, Credentials(user, pass)))

    @Test
    fun discoversPrincipalHomeAndCalendars() {
        val d = calDav().discover()
        assertEquals("${baseUrl}$user/", d.homeUrl)
        val cal = assertNotNull(d.calendars.firstOrNull { it.url == calendarUrl })
        assertEquals("Prueba ñ", cal.name)
        assertEquals(0xFFFF7A8A.toInt(), cal.color) // "#FF7A8AFF" es RRGGBBAA
        assertEquals(setOf("VEVENT"), cal.components)
        assertTrue(!cal.readOnly)
        assertNotNull(cal.ctag ?: cal.syncToken)
    }

    @Test
    fun readsEventsWithEtags() {
        val dav = calDav()
        val etags = dav.listEtags(calendarUrl)
        assertEquals(setOf("${calendarUrl}event.ics"), etags.keys)
        val resources = dav.multiget(calendarUrl, etags.keys)
        val event = resources.single()
        assertEquals(etags.values.single(), event.etag)
        assertTrue(event.ics!!.contains("UID:$uid"))
    }

    @Test
    fun seriesWithAnExceptionKeepsOneUid() {
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Liquid Calendar//IT//ES",
            "BEGIN:VEVENT", "UID:serie-$uid", "DTSTAMP:20261001T000000Z",
            "DTSTART;TZID=America/Argentina/Buenos_Aires:20261005T100000", "RRULE:FREQ=WEEKLY;BYDAY=MO", "SUMMARY:Serie", "END:VEVENT",
            "BEGIN:VEVENT", "UID:serie-$uid", "DTSTAMP:20261001T000000Z",
            "RECURRENCE-ID;TZID=America/Argentina/Buenos_Aires:20261012T100000",
            "DTSTART;TZID=America/Argentina/Buenos_Aires:20261012T120000", "SUMMARY:Serie (movida)", "END:VEVENT",
            "END:VCALENDAR", "",
        ).joinToString("\r\n")
        rawRequest("PUT", "${calendarUrl}serie.ics", ics, "text/calendar; charset=utf-8")
        val dav = calDav()
        val fetched = dav.multiget(calendarUrl, listOf("${calendarUrl}serie.ics")).single()
        val events = Ics.parse(fetched.ics!!)
        assertEquals(listOf("serie-$uid", "serie-$uid"), events.map { it.uid })
        assertTrue(events[0].recurring)
        assertNotNull(events[1].recurrenceId)
        assertEquals("serie-$uid", Ics.uid(fetched.ics!!))
    }

    private val client get() = DavClient(server, Credentials(user, password))

    private fun draft(title: String) = EventDraft(
        title, "Aula 5", null,
        DraftTime.Timed(java.time.Instant.parse("2026-11-04T12:00:00Z"), java.time.Instant.parse("2026-11-04T15:00:00Z")),
    )

    @Test
    fun createEditDeleteWithPreconditions() {
        val dav = client
        val newUid = IcsWriter.newUid()
        val url = dav.url("${calendarUrl}$newUid.ics")
        val ics = IcsWriter.create(newUid, draft("Parcial"))
        // Crear: If-None-Match: *. Una segunda creación en la misma URL no pisa nada.
        val etag1 = assertNotNull(dav.put(url, ics, ifNoneMatch = true))
        assertFailsWith<DavException.PreconditionFailed> { dav.put(url, ics, ifNoneMatch = true) }

        // Editar con el ETag correcto.
        val before = IcsWriter.draftOf(Ics.parseResource(ics), newUid, java.time.ZoneOffset.UTC)!!
        val edited = IcsWriter.patch(ics, newUid, before, before.copy(summary = "Parcial (cambiado)"))
        val etag2 = assertNotNull(dav.put(url, edited, ifMatch = etag1))
        assertTrue(etag2 != etag1)

        // Con el ETag viejo: 412 y el servidor queda como estaba.
        assertFailsWith<DavException.PreconditionFailed> { dav.put(url, ics, ifMatch = etag1) }
        assertFailsWith<DavException.PreconditionFailed> { dav.delete(url, ifMatch = etag1) }
        val current = dav.get(url)
        assertEquals(etag2, current.etag)
        assertEquals("Parcial (cambiado)", Ics.parseResource(String(current.body)).components.single().info.summary)

        // Borrar con el ETag correcto.
        dav.delete(url, ifMatch = etag2)
        assertFailsWith<DavException.NotFound> { dav.get(url) }
    }

    @Test
    fun anEventFromAnotherClientSurvivesAnEdit() {
        val original = javaClass.getResource("/ics/davx5.ics")!!.readText()
        val r = Ics.parseResource(original)
        val dav = client
        val url = dav.url("${calendarUrl}davx5.ics")
        val etag = assertNotNull(dav.put(url, original, ifNoneMatch = true))
        val before = IcsWriter.draftOf(r, r.uid!!, java.time.ZoneOffset.UTC)!!
        dav.put(url, IcsWriter.patch(original, r.uid!!, before, before.copy(location = "Otra dirección")), ifMatch = etag)
        val back = String(dav.get(url).body)
        for (m in listOf("ATTENDEE;CN=Ana", "ORGANIZER", "CATEGORIES:Salud,Personal", "X-MOZ-GENERATION", "BEGIN:VALARM", "X-WR-ALARMUID")) {
            assertTrue(m in back.replace("\r\n ", ""), "se perdió $m")
        }
        assertEquals("Otra dirección", Ics.parseResource(back).components.single().info.location)
    }

    @Test
    fun wrongPasswordIsUnauthorized() {
        assertFailsWith<DavException.Unauthorized> { calDav("contraseña-incorrecta").discover() }
    }

    @Test
    fun otherUsersCannotSeeTheCalendar() {
        val other = DavClient(server, Credentials("other", "other-test-pass")) // secret-check: ignore
        assertFailsWith<DavException.Forbidden> { CalDav(other).listEtags(calendarUrl) }
    }
}
