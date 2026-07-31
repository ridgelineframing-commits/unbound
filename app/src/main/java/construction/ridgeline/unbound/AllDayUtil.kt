package construction.ridgeline.unbound

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * CalendarContract stores all-day events against UTC midnight: DTSTART is the
 * start date at 00:00 UTC and DTEND is the midnight AFTER the last day (exclusive).
 * These pure helpers centralize that convention so it can be unit-tested and reused.
 */
object AllDayUtil {

    /** DTSTART for an all-day event on [date]. */
    fun startMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** DTEND (exclusive) for an all-day event whose last covered day is [lastDate]. */
    fun endMillisExclusive(lastDate: LocalDate): Long =
        lastDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The UTC date a stored all-day boundary falls on. */
    fun dateFromUtcMillis(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    /** The last day actually covered, from an exclusive DTEND. Robust to non-midnight ends. */
    fun lastDateFromExclusiveEnd(endMs: Long): LocalDate =
        dateFromUtcMillis(endMs - 1)
}
