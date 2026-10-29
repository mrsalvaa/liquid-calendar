package dev.liquidcalendar.core.dav

import dev.liquidcalendar.core.ServerUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DavClientTest {
    private val server = MockWebServer()
    private val other = MockWebServer()
    private val creds = Credentials("alice", "s3cret-pass")

    @BeforeTest
    fun start() {
        server.start()
        other.start()
    }

    @AfterTest
    fun stop() {
        server.close()
        other.close()
    }

    private fun client(): DavClient {
        val url = (ServerUrl.parse(server.url("/").toString(), allowHttp = true) as ServerUrl.Result.Ok).url
        return DavClient(url, creds)
    }

    private fun multistatus(body: String) = MockResponse.Builder()
        .code(207)
        .addHeader("Content-Type", "application/xml")
        .body("""<?xml version="1.0"?><multistatus xmlns="DAV:">$body</multistatus>""")
        .build()

    @Test
    fun sendsBasicAuthOnFirstRequest() {
        server.enqueue(multistatus(""))
        client().propfind(server.url("/"), 0, listOf(QName(Ns.DAV, "displayname")))
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.headers["Depth"])
        assertEquals(okhttp3.Credentials.basic("alice", "s3cret-pass"), request.headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthorizedIsNeverRetried() {
        // Aunque el servidor ofrezca otro desafío, el cliente no vuelve a intentar.
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"x\"").build())
        server.enqueue(multistatus(""))
        assertFailsWith<DavException.Unauthorized> { client().propfind(server.url("/"), 0, emptyList()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun followsSameOriginRedirectKeepingMethodAndAuth() {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/dav/").build())
        server.enqueue(multistatus(""))
        client().propfind(server.url("/.well-known/caldav"), 0, emptyList())
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/dav/", second.url.encodedPath)
        assertEquals("PROPFIND", second.method)
        assertTrue(second.headers["Authorization"] != null)
    }

    @Test
    fun refusesRedirectToAnotherOriginWithoutContactingIt() {
        // Otro puerto = otro origen: la contraseña no puede llegar ahí.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/steal/").toString()).build())
        assertFailsWith<DavException.RedirectRefused> { client().propfind(server.url("/"), 0, emptyList()) }
        assertEquals(0, other.requestCount)
    }

    @Test
    fun originRules() {
        val base = "https://cal.example.org:8443/".toHttpUrl()
        assertTrue(DavClient.sameOrigin(base, "https://CAL.example.org:8443/user/".toHttpUrl()))
        // De https a http (aunque sea el mismo host), otro host u otro puerto: otro origen.
        assertFalse(DavClient.sameOrigin(base, "http://cal.example.org:8443/".toHttpUrl()))
        assertFalse(DavClient.sameOrigin(base, "https://evil.example.org:8443/".toHttpUrl()))
        assertFalse(DavClient.sameOrigin(base, "https://cal.example.org/".toHttpUrl()))
    }

    @Test
    fun stopsAfterTooManyRedirects() {
        repeat(DavClient.MAX_REDIRECTS + 1) {
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/loop$it/").build())
        }
        assertFailsWith<DavException.TooManyRedirects> { client().propfind(server.url("/"), 0, emptyList()) }
    }

    @Test
    fun retryAfterSecondsOn503() {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "120").build())
        val e = assertFailsWith<DavException.RetryLater> { client().propfind(server.url("/"), 0, emptyList()) }
        assertEquals(503, e.status)
        assertEquals(Duration.ofSeconds(120), e.retryAfter)
    }

    @Test
    fun retryAfterMissingOn429() {
        server.enqueue(MockResponse.Builder().code(429).build())
        val e = assertFailsWith<DavException.RetryLater> { client().propfind(server.url("/"), 0, emptyList()) }
        assertNull(e.retryAfter)
    }

    @Test
    fun parsesRetryAfterHttpDate() {
        val now = Instant.parse("2026-10-29T10:00:00Z")
        assertEquals(Duration.ofMinutes(5), DavClient.parseRetryAfter("Thu, 29 Oct 2026 10:05:00 GMT", now))
        assertEquals(Duration.ZERO, DavClient.parseRetryAfter("Thu, 29 Oct 2026 09:00:00 GMT", now))
        assertEquals(Duration.ofHours(24), DavClient.parseRetryAfter("999999", now))
        assertNull(DavClient.parseRetryAfter("pronto", now))
    }

    @Test
    fun otherErrors() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        assertFailsWith<DavException.ServerError> { client().propfind(server.url("/"), 0, emptyList()) }
        assertFailsWith<DavException.Forbidden> { client().propfind(server.url("/"), 0, emptyList()) }
        assertFailsWith<DavException.NotFound> { client().propfind(server.url("/"), 0, emptyList()) }
    }

    @Test
    fun rejectsTooLargeBodies() {
        server.enqueue(MockResponse.Builder().code(207).body("x".repeat(2000)).build())
        val url = (ServerUrl.parse(server.url("/").toString(), allowHttp = true) as ServerUrl.Result.Ok).url
        val small = DavClient(url, creds, maxBodyBytes = 1000)
        assertFailsWith<DavException.TooLarge> { small.propfind(server.url("/"), 0, emptyList()) }
    }

    @Test
    fun badXmlIsAnErrorNotACrash() {
        server.enqueue(MockResponse.Builder().code(207).body("<multistatus xmlns='DAV:'><response>").build())
        assertFailsWith<DavException.BadResponse> { client().propfind(server.url("/"), 0, emptyList()) }
    }

    @Test
    fun networkErrorsDoNotLeakTheUrl() {
        val url = server.url("/")
        server.close()
        val e = assertFailsWith<DavException.Network> { client().propfind(url, 0, emptyList()) }
        assertTrue(url.host !in e.message!! && url.port.toString() !in e.message!!)
    }

    @Test
    fun keepsOnlySuccessfulProps() {
        server.enqueue(
            multistatus(
                """<response><href>/a/</href>
                <propstat><prop><displayname>Casa</displayname></prop><status>HTTP/1.1 200 OK</status></propstat>
                <propstat><prop><getctag xmlns="http://calendarserver.org/ns/"/></prop><status>HTTP/1.1 404 Not Found</status></propstat>
                </response>""",
            ),
        )
        val r = client().propfind(server.url("/"), 1, emptyList()).single()
        assertEquals("Casa", r[QName(Ns.DAV, "displayname")]?.text)
        assertNull(r[QName(Ns.CALSERVER, "getctag")])
        server.takeRequest(1, TimeUnit.SECONDS)
    }
}
