package construction.ridgeline.unbound

import construction.ridgeline.unbound.TimelineLayout.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineLayoutTest {

    private fun place(vararg s: Span) = TimelineLayout.place(s.toList()).associateBy { it.index }

    @Test fun emptyIsEmpty() = assertEquals(0, TimelineLayout.place(emptyList()).size)

    @Test fun singleEventFullWidth() {
        val p = place(Span(0, 540, 600))
        assertEquals(0, p[0]!!.col); assertEquals(1, p[0]!!.cols)
    }

    @Test fun nonOverlappingShareColumnZero() {
        val p = place(Span(0, 540, 600), Span(1, 600, 660)) // 9-10, 10-11 (touching, not overlapping)
        assertEquals(1, p[0]!!.cols)
        assertEquals(1, p[1]!!.cols)
        assertEquals(0, p[0]!!.col); assertEquals(0, p[1]!!.col)
    }

    @Test fun twoOverlappingGetTwoColumns() {
        val p = place(Span(0, 540, 660), Span(1, 600, 720)) // 9-11 and 10-12 overlap
        assertEquals(2, p[0]!!.cols); assertEquals(2, p[1]!!.cols)
        assertEquals(0, p[0]!!.col); assertEquals(1, p[1]!!.col)
    }

    @Test fun separateClustersHaveSeparateCounts() {
        // morning pair overlaps (cols=2); afternoon single (cols=1)
        val p = place(Span(0, 540, 660), Span(1, 600, 720), Span(2, 900, 960))
        assertEquals(2, p[0]!!.cols)
        assertEquals(2, p[1]!!.cols)
        assertEquals(1, p[2]!!.cols)
        assertEquals(0, p[2]!!.col)
    }

    @Test fun freedColumnIsReused() {
        // A: 9-10, B: 9-11, C: 10-11. B (longer) takes col 0, A col 1; C starts when A
        // ends (10:00) and reuses A's column (col 1). Cluster width stays 2.
        val p = place(Span(0, 540, 600), Span(1, 540, 660), Span(2, 600, 660))
        assertEquals(2, p[0]!!.cols)
        assertEquals(1, p[2]!!.col)
    }
}
