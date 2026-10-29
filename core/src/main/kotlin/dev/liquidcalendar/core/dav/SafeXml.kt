package dev.liquidcalendar.core.dav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/** Nombre XML completo: espacio de nombres + nombre local (los prefijos no importan). */
data class QName(val ns: String, val name: String) {
    override fun toString(): String = "{$ns}$name"
}

object Ns {
    const val DAV = "DAV:"
    const val CALDAV = "urn:ietf:params:xml:ns:caldav"
    const val CALSERVER = "http://calendarserver.org/ns/"
    const val APPLE_ICAL = "http://apple.com/ns/ical/"
}

/** Árbol mínimo de un documento XML ya leído. */
class XmlElement(
    val name: QName,
    val children: List<XmlElement>,
    val text: String,
    /** Atributos sin espacio de nombres (p. ej. name en <comp name="VEVENT"/>). */
    val attributes: Map<String, String> = emptyMap(),
) {
    fun child(q: QName): XmlElement? = children.firstOrNull { it.name == q }
    fun all(q: QName): List<XmlElement> = children.filter { it.name == q }
    fun has(q: QName): Boolean = children.any { it.name == q }
}

class XmlException(message: String) : Exception(message)

/**
 * Lector de XML para respuestas del servidor, pensado para datos no confiables: rechaza cualquier
 * DOCTYPE (sin DTD no hay entidades externas ni expansiones del tipo "billion laughs") y limita el
 * tamaño, la cantidad de elementos y la profundidad. Nunca accede a la red ni a archivos.
 */
object SafeXml {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ELEMENTS = 500_000
    const val MAX_DEPTH = 64

    fun parse(bytes: ByteArray): XmlElement {
        if (bytes.size > MAX_BYTES) throw XmlException("XML demasiado grande")
        val parser = try {
            XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        } catch (e: XmlPullParserException) {
            throw XmlException("No hay lector de XML")
        }
        // Sin procesar DOCTYPE ni validar contra DTDs (por defecto ya es así; se deja explícito).
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), null)

        // Pila de elementos abiertos: nombre, hijos y texto acumulado.
        class Open(
            val name: QName,
            val attributes: Map<String, String>,
            val children: MutableList<XmlElement> = mutableListOf(),
            val text: StringBuilder = StringBuilder(),
        )
        val stack = ArrayDeque<Open>()
        var root: XmlElement? = null
        var elements = 0
        try {
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL -> throw XmlException("DOCTYPE no permitido")
                    XmlPullParser.START_TAG -> {
                        if (root != null) throw XmlException("Contenido después del elemento raíz")
                        if (++elements > MAX_ELEMENTS) throw XmlException("Demasiados elementos")
                        if (stack.size >= MAX_DEPTH) throw XmlException("Anidamiento demasiado profundo")
                        val attributes = (0 until parser.attributeCount)
                            .filter { parser.getAttributeNamespace(it).isNullOrEmpty() }
                            .associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                        stack.addLast(Open(QName(parser.namespace.orEmpty(), parser.name), attributes))
                    }
                    XmlPullParser.END_TAG -> {
                        val open = stack.removeLastOrNull() ?: throw XmlException("Etiqueta de cierre sin abrir")
                        val element = XmlElement(open.name, open.children, open.text.toString().trim(), open.attributes)
                        if (stack.isEmpty()) root = element else stack.last().children += element
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.lastOrNull()?.text?.append(parser.text)
                    XmlPullParser.ENTITY_REF -> {
                        // Solo las entidades predefinidas (&amp; &lt; ...) y las de carácter tienen texto.
                        val text = parser.text ?: throw XmlException("Entidad desconocida")
                        stack.lastOrNull()?.text?.append(text)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                    else -> Unit // comentarios, instrucciones de proceso, espacios ignorables
                }
            }
        } catch (e: XmlPullParserException) {
            throw XmlException("XML mal formado")
        } catch (e: java.io.IOException) {
            throw XmlException("XML ilegible")
        }
        return root ?: throw XmlException("XML vacío")
    }
}

/** Escapa texto para meterlo dentro de un elemento XML. */
fun xmlEscape(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
