package dev.liquidcalendar.core.feed

import dev.liquidcalendar.core.dav.DavException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FeedsTest {
    private val server = MockWebServer()
    private val other = MockWebServer()

    @BeforeTest fun start() { server.start(); other.start() }

    @AfterTest fun stop() { server.close(); other.close() }

    private fun url(path: String = "/feriados.ics") = (FeedUrl.parse(server.url(path).toString(), allowHttp = true) as FeedUrl.Result.Ok).url

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"

    @Test
    fun urls() {
        assertEquals("https://cal.example.org/feriados.ics", (FeedUrl.parse("webcal://cal.example.org/feriados.ics") as FeedUrl.Result.Ok).url.toString())
        assertEquals("https://cal.example.org/x.ics?token=abc", (FeedUrl.parse(" webcals://cal.example.org/x.ics?token=abc ") as FeedUrl.Result.Ok).url.toString())
        assertEquals(FeedUrl.Reason.HTTP_NOT_ALLOWED, (FeedUrl.parse("http://cal.example.org/x.ics") as FeedUrl.Result.Invalid).reason)
        assertEquals(FeedUrl.Reason.CREDENTIALS_IN_URL, (FeedUrl.parse("https://yo:clave@cal.example.org/x.ics") as FeedUrl.Result.Invalid).reason)
        assertEquals(FeedUrl.Reason.UNSUPPORTED_SCHEME, (FeedUrl.parse("ftp://cal.example.org/x.ics") as FeedUrl.Result.Invalid).reason)
        assertEquals(FeedUrl.Reason.EMPTY, (FeedUrl.parse("  ") as FeedUrl.Result.Invalid).reason)
        assertEquals("cal.example.org", (FeedUrl.parse("webcal://cal.example.org/x.ics?token=secreto") as FeedUrl.Result.Ok).url.host)
    }

    @Test
    fun sendsNothingThatIdentifiesTheUserAndUsesConditionalRequests() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"v2\"").addHeader("Last-Modified", "Thu, 29 Oct 2026 10:00:00 GMT").body(ics).build())
        val r = assertIs<FeedResult.Updated>(FeedClient().fetch(url(), etag = "\"v1\"", lastModified = "Wed, 28 Oct 2026 10:00:00 GMT"))
        assertEquals("\"v2\"", r.etag)
        val req = server.takeRequest()
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["Cookie"])
        assertEquals("\"v1\"", req.headers["If-None-Match"])
        assertEquals("Wed, 28 Oct 2026 10:00:00 GMT", req.headers["If-Modified-Since"])
        assertEquals("LiquidCalendar", req.headers["User-Agent"])
    }

    @Test
    fun notModified() {
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(FeedResult.NotModified, FeedClient().fetch(url(), etag = "\"v1\""))
    }

    @Test
    fun tooLarge() {
        server.enqueue(MockResponse.Builder().code(200).body("X".repeat(3000)).build())
        assertFailsWith<DavException.TooLarge> { FeedClient(maxBytes = 1000).fetch(url()) }
    }

    @Test
    fun retryAfterIsReported() {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "3600").build())
        assertEquals(Duration.ofHours(1), assertFailsWith<DavException.RetryLater> { FeedClient().fetch(url()) }.retryAfter)
    }

    @Test
    fun redirectToAnotherHostNeedsConfirmationAndContactsNobody() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/feriados.ics").toString()).build())
        val r = assertIs<FeedResult.NeedsConfirmation>(FeedClient().fetch(url()))
        assertEquals(other.url("/feriados.ics").toString(), r.to.toString())
        assertEquals(0, other.requestCount)
        // Una vez aceptado ese host, se sigue.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/feriados.ics").toString()).build())
        other.enqueue(MockResponse.Builder().code(200).body(ics).build())
        assertIs<FeedResult.Updated>(FeedClient().fetch(url(), acceptedHosts = setOf(r.to.authority)))
    }

    @Test
    fun redirectRules() {
        val https = "https://cal.example.org/a.ics".toHttpUrl()
        assertEquals(FeedClient.Redirect.FOLLOW, FeedClient.redirect(https, "https://cal.example.org/b.ics".toHttpUrl(), emptySet()))
        assertEquals(FeedClient.Redirect.REFUSE, FeedClient.redirect(https, "http://cal.example.org/a.ics".toHttpUrl(), emptySet()))
        assertEquals(FeedClient.Redirect.ASK, FeedClient.redirect(https, "https://otro.example.org/a.ics".toHttpUrl(), emptySet()))
        assertEquals(FeedClient.Redirect.FOLLOW, FeedClient.redirect(https, "https://otro.example.org/a.ics".toHttpUrl(), setOf("OTRO.example.org")))
        // Ni aun aceptado ese host se baja a http.
        assertEquals(FeedClient.Redirect.REFUSE, FeedClient.redirect(https, "http://otro.example.org/a.ics".toHttpUrl(), setOf("otro.example.org")))
    }
}
