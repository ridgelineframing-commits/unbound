package construction.ridgeline.unbound

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AllDayUtilTest {

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun startIsUtcMidnight() =
        assertEquals(utc("2026-08-01T00:00:00Z"), AllDayUtil.startMillis(LocalDate.of(2026, 8, 1)))

    @Test fun endIsExclusiveNextMidnight() =
        assertEquals(utc("2026-08-02T00:00:00Z"), AllDayUtil.endMillisExclusive(LocalDate.of(2026, 8, 1)))

    @Test fun endSpansMultipleDays() =
        assertEquals(utc("2026-08-04T00:00:00Z"), AllDayUtil.endMillisExclusive(LocalDate.of(2026, 8, 3)))

    @Test fun dateRoundTrips() =
        assertEquals(LocalDate.of(2026, 8, 1), AllDayUtil.dateFromUtcMillis(AllDayUtil.startMillis(LocalDate.of(2026, 8, 1))))

    @Test fun lastDaySingle() =
        assertEquals(LocalDate.of(2026, 8, 1),
            AllDayUtil.lastDateFromExclusiveEnd(AllDayUtil.endMillisExclusive(LocalDate.of(2026, 8, 1))))

    @Test fun lastDayMulti() =
        assertEquals(LocalDate.of(2026, 8, 3),
            AllDayUtil.lastDateFromExclusiveEnd(AllDayUtil.endMillisExclusive(LocalDate.of(2026, 8, 3))))

    @Test fun crossesMonthBoundary() =
        assertEquals(LocalDate.of(2026, 2, 28),
            AllDayUtil.lastDateFromExclusiveEnd(AllDayUtil.endMillisExclusive(LocalDate.of(2026, 2, 28))))
}
