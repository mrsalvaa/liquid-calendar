package dev.liquidcalendar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactTest {
    @Test
    fun hidesAuthorizationHeaders() {
        assertEquals("Authorization: ***", Redact.text("Authorization: Basic dXNlcjpwYXNz")) // secret-check: ignore (dato de prueba)
        assertEquals("proxy-authorization: ***", Redact.text("proxy-authorization: Bearer abc.def"))
    }

    @Test
    fun hidesCookies() {
        assertEquals("Set-Cookie: ***", Redact.text("Set-Cookie: session=abc; HttpOnly"))
    }

    @Test
    fun hidesCredentialsInUrls() {
        assertEquals(
            "GET https://***@cal.example.org/user/",
            Redact.text("GET https://user:hunter2@cal.example.org/user/"),
        )
    }

    @Test
    fun hidesExplicitSecrets() {
        val out = Redact.text("401 for alice with pw correct-horse at /alice/", listOf("alice", "correct-horse"))
        assertEquals("401 for *** with pw *** at /***/", out)
    }

    @Test
    fun ignoresTooShortSecrets() {
        // Un secreto de 1 o 2 caracteres taparía medio log sin proteger nada.
        assertFalse(Redact.text("a b c", listOf("a")).contains(Redact.MASK))
    }
}
