package dev.liquidcalendar.core

/**
 * Quita secretos de un texto antes de escribirlo en un log: cabeceras de autenticación,
 * credenciales dentro de URLs y cualquier valor de [secrets] (p. ej. la contraseña o el usuario).
 */
object Redact {
    const val MASK = "***"

    private val authHeader = Regex("""(?i)\b((?:proxy-)?authorization)\s*[:=]\s*(\S+)(?:\s+\S+)?""")
    private val urlUserInfo = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s@]+@""")
    private val cookie = Regex("""(?i)\b(set-cookie|cookie)\s*:\s*[^\r\n]+""")

    fun text(input: String, secrets: Collection<String> = emptyList()): String {
        var out = input
        // Los secretos explícitos primero, de más largo a más corto, para no dejar restos.
        secrets.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { out = out.replace(it, MASK) }
        out = authHeader.replace(out) { "${it.groupValues[1]}: $MASK" }
        out = cookie.replace(out) { "${it.groupValues[1]}: $MASK" }
        out = urlUserInfo.replace(out) { "${it.groupValues[1]}$MASK@" }
        return out
    }
}
