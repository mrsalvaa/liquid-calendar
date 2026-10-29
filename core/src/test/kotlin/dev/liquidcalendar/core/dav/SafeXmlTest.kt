package dev.liquidcalendar.core.dav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeXmlTest {
    private fun parse(s: String) = SafeXml.parse(s.toByteArray())

    @Test
    fun resolvesNamespacesNotPrefixes() {
        // Radicale usa DAV: como espacio de nombres por defecto; otros servidores usan "D:" o "d:".
        val a = parse("""<multistatus xmlns="DAV:"><response/></multistatus>""")
        val b = parse("""<D:multistatus xmlns:D="DAV:"><D:response/></D:multistatus>""")
        assertEquals(QName(Ns.DAV, "multistatus"), a.name)
        assertEquals(a.name, b.name)
        assertEquals(a.children.single().name, b.children.single().name)
    }

    @Test
    fun keepsTextAttributesAndPredefinedEntities() {
        val e = parse("""<c:comp xmlns:c="urn:ietf:params:xml:ns:caldav" name="VEVENT">a &amp; b &lt;c&gt; &#233;</c:comp>""")
        assertEquals("a & b <c> é", e.text)
        assertEquals("VEVENT", e.attributes["name"])
    }

    @Test
    fun rejectsExternalEntities() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <multistatus xmlns="DAV:">&xxe;</multistatus>"""
        assertFailsWith<XmlException> { parse(xxe) }
    }

    @Test
    fun rejectsEntityExpansion() {
        val laughs = """<?xml version="1.0"?>
            <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
            <lolz>&lol2;</lolz>"""
        assertFailsWith<XmlException> { parse(laughs) }
    }

    @Test
    fun rejectsUndeclaredEntities() {
        assertFailsWith<XmlException> { parse("<a>&nope;</a>") }
    }

    @Test
    fun rejectsTooDeepDocuments() {
        val deep = "<a>".repeat(SafeXml.MAX_DEPTH + 1) + "</a>".repeat(SafeXml.MAX_DEPTH + 1)
        assertFailsWith<XmlException> { parse(deep) }
    }

    @Test
    fun malformedOrEmptyIsAnException() {
        listOf("", "<a>", "<a></b>", "no es xml", "<a/><b/>").forEach { input ->
            assertFailsWith<XmlException>(input) { parse(input) }
        }
    }

    @Test
    fun escapesText() {
        assertEquals("&lt;a href=&quot;x&quot;&gt; &amp; &apos;", xmlEscape("<a href=\"x\"> & '"))
        assertTrue(xmlEscape("/user/cal ñ/").contains("ñ"))
    }
}
