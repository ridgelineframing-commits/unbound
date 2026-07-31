package construction.ridgeline.unbound

/**
 * Pure RFC 5545 DURATION handling — no Android dependencies, so it's unit-testable
 * on the JVM. Supports the practical subset providers emit: weeks, days, and a
 * time part of hours/minutes/seconds, with an optional sign.
 */
object DurationUtil {

    private val PATTERN = Regex(
        "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$"
    )

    private const val MS_SEC = 1000L
    private const val MS_MIN = 60 * MS_SEC
    private const val MS_HOUR = 60 * MS_MIN
    private const val MS_DAY = 24 * MS_HOUR
    private const val MS_WEEK = 7 * MS_DAY

    /**
     * Parse a DURATION to milliseconds. Returns the fallback (1 day for all-day,
     * else 1 hour) when the string is null/blank/malformed or resolves to <= 0.
     * A negative sign is treated as its absolute magnitude.
     */
    fun toMillis(duration: String?, fallbackAllDay: Boolean): Long {
        val fallback = if (fallbackAllDay) MS_DAY else MS_HOUR
        val s = duration?.trim().orEmpty()
        if (s.isEmpty()) return fallback
        val m = PATTERN.matchEntire(s) ?: return fallback
        // A bare "P" or "PT" with no components is invalid.
        if (m.groupValues.drop(2).all { it.isEmpty() }) return fallback
        val g = m.groupValues
        fun num(i: Int) = g[i].toLongOrNull() ?: 0L
        val ms = num(2) * MS_WEEK + num(3) * MS_DAY + num(4) * MS_HOUR + num(5) * MS_MIN + num(6) * MS_SEC
        return if (ms > 0) ms else fallback
    }

    /** Format a span for storage: all-day events use whole days (P{n}D), timed use PT{n}S. */
    fun format(ms: Long, allDay: Boolean): String {
        return if (allDay) {
            val days = (ms / MS_DAY).coerceAtLeast(1)
            "P${days}D"
        } else {
            val secs = (ms / MS_SEC).coerceAtLeast(1)
            "PT${secs}S"
        }
    }
}
