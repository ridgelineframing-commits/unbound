package construction.ridgeline.unbound

import construction.ridgeline.unbound.RecurrenceUtil.Repeat
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurrenceUtilTest {

    @Test fun nullIsNone() = assertEquals(Repeat.NONE, RecurrenceUtil.classify(null))
    @Test fun blankIsNone() = assertEquals(Repeat.NONE, RecurrenceUtil.classify("  "))

    @Test fun simpleDaily() = assertEquals(Repeat.DAILY, RecurrenceUtil.classify("FREQ=DAILY"))
    @Test fun simpleWeekly() = assertEquals(Repeat.WEEKLY, RecurrenceUtil.classify("FREQ=WEEKLY"))
    @Test fun simpleMonthly() = assertEquals(Repeat.MONTHLY, RecurrenceUtil.classify("FREQ=MONTHLY"))
    @Test fun simpleYearly() = assertEquals(Repeat.YEARLY, RecurrenceUtil.classify("FREQ=YEARLY"))

    @Test fun intervalOneWithWkstIsStillSimple() =
        assertEquals(Repeat.WEEKLY, RecurrenceUtil.classify("FREQ=WEEKLY;INTERVAL=1;WKST=SU"))

    @Test fun intervalTwoIsCustom() =
        assertEquals(Repeat.CUSTOM, RecurrenceUtil.classify("FREQ=WEEKLY;INTERVAL=2"))
    @Test fun bydayIsCustom() =
        assertEquals(Repeat.CUSTOM, RecurrenceUtil.classify("FREQ=WEEKLY;BYDAY=MO,WE,FR"))
    @Test fun countIsCustom() =
        assertEquals(Repeat.CUSTOM, RecurrenceUtil.classify("FREQ=DAILY;COUNT=10"))
    @Test fun untilIsCustom() =
        assertEquals(Repeat.CUSTOM, RecurrenceUtil.classify("FREQ=DAILY;UNTIL=20261231T000000Z"))
    @Test fun missingFreqIsCustom() =
        assertEquals(Repeat.CUSTOM, RecurrenceUtil.classify("INTERVAL=2"))

    @Test fun ruleForRoundTrips() {
        assertEquals("FREQ=DAILY", RecurrenceUtil.ruleFor(Repeat.DAILY))
        assertEquals("FREQ=WEEKLY", RecurrenceUtil.ruleFor(Repeat.WEEKLY))
        assertEquals("FREQ=MONTHLY", RecurrenceUtil.ruleFor(Repeat.MONTHLY))
        assertEquals("FREQ=YEARLY", RecurrenceUtil.ruleFor(Repeat.YEARLY))
        assertEquals(null, RecurrenceUtil.ruleFor(Repeat.NONE))
        assertEquals(null, RecurrenceUtil.ruleFor(Repeat.CUSTOM))
    }
}
