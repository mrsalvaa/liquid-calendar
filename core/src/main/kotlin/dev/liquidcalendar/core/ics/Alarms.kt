package dev.liquidcalendar.core.ics

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Cuándo suena un recordatorio. */
sealed interface Trigger {
    /** Respecto del inicio (o del fin, si [fromEnd]); negativo = antes. */
    data class Relative(val offset: Duration, val fromEnd: Boolean = false) : Trigger

    /** En un instante fijo (TRIGGER;VALUE=DATE-TIME). */
    data class Absolute(val at: Instant) : Trigger
}

/**
 * Un VALARM. [key] identifica al que ya estaba en el archivo (por su texto exacto): si no se
 * toca, se conserva byte por byte. null = recordatorio nuevo.
 */
data class AlarmSpec(val trigger: Trigger, val key: String? = null)

object Alarms {
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Cuándo suena [alarm] para una ocurrencia (todo el día: desde la medianoche local). */
    fun triggerAt(alarm: AlarmSpec, time: When, zone: ZoneId): Instant {
        val start: Instant
        val end: Instant
        when (time) {
            is When.AllDay -> {
                start = time.first.atStartOfDay(zone).toInstant()
                end = time.endExclusive.atStartOfDay(zone).toInstant()
            }
            is When.Timed -> {
                start = time.start
                end = time.end
            }
        }
        return when (val t = alarm.trigger) {
            is Trigger.Absolute -> t.at
            is Trigger.Relative -> (if (t.fromEnd) end else start).plus(t.offset)
        }
    }

    /** Clave estable de un bloque VALARM: su texto, sin plegado ni espacios de fin de línea. */
    fun keyOf(lines: List<String>): String = lines.joinToString("\n") { Doc.unfold(it).trimEnd() }.hashCode().toString(16)

    /** Lee el TRIGGER de un bloque VALARM (líneas ya desplegadas). null si no se entiende. */
    fun parse(lines: List<String>): AlarmSpec? {
        val trigger = lines.map(Doc::unfold).firstOrNull { it.substringBefore(':').substringBefore(';').equals("TRIGGER", true) } ?: return null
        val params = trigger.substringBefore(':').split(';').drop(1).map { it.uppercase() }
        val value = trigger.substringAfter(':').trim()
        val t = if (params.any { it == "VALUE=DATE-TIME" }) {
            try {
                Trigger.Absolute(
                    if (value.endsWith("Z")) LocalDateTime.parse(value.removeSuffix("Z"), LOCAL).toInstant(ZoneOffset.UTC)
                    else LocalDateTime.parse(value, LOCAL).toInstant(ZoneOffset.UTC),
                )
            } catch (e: Exception) {
                return null
            }
        } else {
            Trigger.Relative(duration(value) ?: return null, fromEnd = params.any { it == "RELATED=END" })
        }
        return AlarmSpec(t, keyOf(lines))
    }

    /** VALARM nuevo (aviso en pantalla) para escribir en el archivo. */
    fun block(alarm: AlarmSpec, summary: String): List<String> = listOf(
        "BEGIN:VALARM",
        when (val t = alarm.trigger) {
            is Trigger.Relative -> "TRIGGER" + (if (t.fromEnd) ";RELATED=END" else "") + ":" + format(t.offset)
            is Trigger.Absolute -> "TRIGGER;VALUE=DATE-TIME:${UTC.format(t.at)}"
        },
        "ACTION:DISPLAY",
        "DESCRIPTION:${IcsWriter.text(summary.ifBlank { "Recordatorio" })}",
        "END:VALARM",
    )

    /** "-PT15M", "-P1D", "-P1W", "PT0S", "-P1DT2H30M" → Duration. */
    fun duration(text: String): Duration? {
        val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").matchEntire(text.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        if (listOf(w, d, h, min, s).all { it.isEmpty() }) return null
        val total = Duration.ofDays((w.toLongOrNull() ?: 0) * 7 + (d.toLongOrNull() ?: 0))
            .plusHours(h.toLongOrNull() ?: 0).plusMinutes(min.toLongOrNull() ?: 0).plusSeconds(s.toLongOrNull() ?: 0)
        return if (sign == "-") total.negated() else total
    }

    /** Duration → valor iCalendar ("-PT15M", "-P1D", "PT0S"). */
    fun format(d: Duration): String {
        val neg = d.isNegative
        var rest = d.abs().seconds
        val days = rest / 86400
        rest %= 86400
        val h = rest / 3600
        val m = (rest % 3600) / 60
        val s = rest % 60
        val time = buildString {
            if (h > 0) append("${h}H")
            if (m > 0) append("${m}M")
            if (s > 0) append("${s}S")
        }
        val body = when {
            days > 0 && time.isEmpty() -> "P${days}D"
            days > 0 -> "P${days}DT$time"
            time.isEmpty() -> "PT0S"
            else -> "PT$time"
        }
        return (if (neg) "-" else "") + body
    }
}
