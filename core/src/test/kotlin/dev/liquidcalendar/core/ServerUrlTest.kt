package dev.liquidcalendar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerUrlTest {
    private fun ok(input: String, allowHttp: Boolean = false): ServerUrl =
        assertIs<ServerUrl.Result.Ok>(ServerUrl.parse(input, allowHttp)).url

    private fun invalid(input: String, allowHttp: Boolean = false): ServerUrl.Reason =
        assertIs<ServerUrl.Result.Invalid>(ServerUrl.parse(input, allowHttp)).reason

    @Test
    fun keepsNonStandardPortAndAddsSlash() {
        val url = ok("https://cal.example.org:8443")
        assertEquals("https://cal.example.org:8443/", url.toString())
        assertEquals(8443, url.port)
        assertTrue(url.isHttps)
    }

    @Test
    fun assumesHttpsWithoutScheme() {
        assertEquals("https://cal.example.org:8443/user/", ok("  cal.example.org:8443/user ").toString())
    }

    @Test
    fun lowercasesSchemeAndHostButNotPath() {
        assertEquals("https://cal.example.org/User/Cal/", ok("HTTPS://Cal.Example.ORG/User/Cal").toString())
    }

    @Test
    fun defaultPorts() {
        assertEquals(443, ok("https://cal.example.org").port)
        assertEquals(80, ok("http://192.168.1.10", allowHttp = true).port)
    }

    @Test
    fun httpNeedsExplicitOptIn() {
        assertEquals(ServerUrl.Reason.HTTP_NOT_ALLOWED, invalid("http://192.168.1.10:5232"))
        assertFalse(ok("http://192.168.1.10:5232", allowHttp = true).isHttps)
    }

    @Test
    fun rejectsBadInput() {
        assertEquals(ServerUrl.Reason.EMPTY, invalid("   "))
        assertEquals(ServerUrl.Reason.UNSUPPORTED_SCHEME, invalid("ftp://cal.example.org"))
        assertEquals(ServerUrl.Reason.UNSUPPORTED_SCHEME, invalid("webcal://cal.example.org"))
        assertEquals(ServerUrl.Reason.CREDENTIALS_IN_URL, invalid("https://user:secret@cal.example.org"))
        assertEquals(ServerUrl.Reason.QUERY_OR_FRAGMENT, invalid("https://cal.example.org/?a=1"))
        assertEquals(ServerUrl.Reason.QUERY_OR_FRAGMENT, invalid("https://cal.example.org/#x"))
        assertEquals(ServerUrl.Reason.BAD_PORT, invalid("https://cal.example.org:99999"))
        assertEquals(ServerUrl.Reason.BAD_PORT, invalid("https://cal.example.org:abc"))
        assertEquals(ServerUrl.Reason.MALFORMED, invalid("https://cal example.org"))
    }

    @Test
    fun resolvesServerHrefs() {
        val base = ok("https://cal.example.org:8443/")
        assertEquals("https://cal.example.org:8443/user/cal/", base.resolve("/user/cal/").toString())
    }

    @Test
    fun localHosts() {
        listOf("localhost", "127.0.0.1", "192.168.1.10", "10.0.0.2", "172.16.5.4", "nas.local", "pi.lan", "[::1]", "fd00::1")
            .forEach { assertTrue(ServerUrl.isLocalHost(it), it) }
        listOf("cal.example.org", "8.8.8.8", "172.32.0.1", "2001:db8::1")
            .forEach { assertFalse(ServerUrl.isLocalHost(it), it) }
    }
}
