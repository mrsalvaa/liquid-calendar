package dev.liquidcalendar.core.dav

import kotlin.test.Test
import kotlin.test.assertEquals

class DavExceptionTest {
    @Test
    fun codesAreStableAndUnique() {
        // El código se guarda en la base y se registra: tiene que sobrevivir a la ofuscación de R8,
        // así que va escrito a mano y debe coincidir con el nombre de la clase.
        val all = listOf(
            DavException.Unauthorized(), DavException.Forbidden(), DavException.NotFound(),
            DavException.PreconditionFailed(), DavException.RetryLater(503, null), DavException.ServerError(500),
            DavException.UnexpectedStatus(418), DavException.RedirectRefused(), DavException.TooManyRedirects(),
            DavException.Network("Timeout"), DavException.Tls("SSLHandshakeException"), DavException.TooLarge(),
            DavException.BadResponse("x"),
        )
        all.forEach { assertEquals(it.javaClass.simpleName, it.code) }
        assertEquals(all.size, all.map { it.code }.toSet().size)
        // Si se agrega un tipo nuevo de error y no está en la lista, falla.
        assertEquals(DavException::class.java.permittedSubclasses.size, all.size)
    }
}
