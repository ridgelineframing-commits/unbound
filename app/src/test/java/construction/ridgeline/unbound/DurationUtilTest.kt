package construction.ridgeline.unbound

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationUtilTest {

    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    @Test fun parsesSeconds() = assertEquals(HOUR, DurationUtil.toMillis("PT3600S", false))
    @Test fun parsesHours() = assertEquals(HOUR, DurationUtil.toMillis("PT1H", false))
    @Test fun parsesMinutes() = assertEquals(1_800_000L, DurationUtil.toMillis("PT30M", false))
    @Test fun parsesHoursAndMinutes() = assertEquals(5_400_000L, DurationUtil.toMillis("PT1H30M", false))
    @Test fun parsesDays() = assertEquals(DAY, DurationUtil.toMillis("P1D", true))
    @Test fun parsesDayAndTime() = assertEquals(DAY + 2 * HOUR, DurationUtil.toMillis("P1DT2H", false))
    @Test fun parsesWeeks() = assertEquals(14 * DAY, DurationUtil.toMillis("P2W", false))
    @Test fun negativeSignTreatedAsMagnitude() = assertEquals(HOUR, DurationUtil.toMillis("-PT1H", false))

    @Test fun nullFallsBackTimed() = assertEquals(HOUR, DurationUtil.toMillis(null, false))
    @Test fun nullFallsBackAllDay() = assertEquals(DAY, DurationUtil.toMillis(null, true))
    @Test fun garbageFallsBack() = assertEquals(HOUR, DurationUtil.toMillis("nonsense", false))
    @Test fun emptyDurationBodyFallsBack() = assertEquals(DAY, DurationUtil.toMillis("P", true))
    @Test fun emptyTimeBodyFallsBack() = assertEquals(HOUR, DurationUtil.toMillis("PT", false))

    @Test fun formatsTimed() = assertEquals("PT3600S", DurationUtil.format(HOUR, false))
    @Test fun formatsAllDaySingle() = assertEquals("P1D", DurationUtil.format(DAY, true))
    @Test fun formatsAllDayMulti() = assertEquals("P2D", DurationUtil.format(2 * DAY, true))
    @Test fun formatClampsToAtLeastOne() = assertEquals("P1D", DurationUtil.format(0, true))
}
