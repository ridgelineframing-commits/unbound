package construction.ridgeline.unbound

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchUtilTest {

    @Test fun plainTextUnchanged() = assertEquals("meeting", SearchUtil.escapeLike("meeting"))
    @Test fun escapesPercent() = assertEquals("50\\% off", SearchUtil.escapeLike("50% off"))
    @Test fun escapesUnderscore() = assertEquals("a\\_b", SearchUtil.escapeLike("a_b"))
    @Test fun escapesBackslashFirst() = assertEquals("a\\\\b", SearchUtil.escapeLike("a\\b"))
    @Test fun escapesCombination() = assertEquals("\\%\\_\\\\", SearchUtil.escapeLike("%_\\"))
    @Test fun emptyStays() = assertEquals("", SearchUtil.escapeLike(""))
}
