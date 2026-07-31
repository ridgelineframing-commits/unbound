package construction.ridgeline.unbound

/**
 * Pure column layout for overlapping timed events on a day timeline — no Android
 * deps, so it's unit-testable. Each event is placed in the lowest free column;
 * events that transitively overlap form a cluster and share a column count, so a
 * view can compute each event's x/width as col/cols of the available track.
 */
object TimelineLayout {

    data class Span(val index: Int, val startMin: Int, val endMin: Int)
    data class Placement(val index: Int, val col: Int, val cols: Int)

    fun place(spans: List<Span>): List<Placement> {
        if (spans.isEmpty()) return emptyList()
        // Stable order: by start, then longer first.
        val sorted = spans.sortedWith(compareBy({ it.startMin }, { -(it.endMin - it.startMin) }))
        val out = ArrayList<Placement>(spans.size)

        var clusterStart = 0
        var clusterEnd = Int.MIN_VALUE
        val cluster = ArrayList<Span>()

        fun flush() {
            if (cluster.isEmpty()) return
            // Greedy column assignment; a column is free once its last event ends.
            val colEnds = ArrayList<Int>()      // end minute of the last event in each column
            val assigned = IntArray(cluster.size)
            cluster.forEachIndexed { i, s ->
                var col = colEnds.indexOfFirst { it <= s.startMin }
                if (col == -1) { col = colEnds.size; colEnds.add(s.endMin) } else { colEnds[col] = s.endMin }
                assigned[i] = col
            }
            val cols = colEnds.size
            cluster.forEachIndexed { i, s -> out.add(Placement(s.index, assigned[i], cols)) }
            cluster.clear()
        }

        for (s in sorted) {
            if (cluster.isNotEmpty() && s.startMin >= clusterEnd) {
                flush(); clusterEnd = Int.MIN_VALUE
            }
            cluster.add(s)
            clusterStart = if (cluster.size == 1) s.startMin else clusterStart
            clusterEnd = maxOf(clusterEnd, s.endMin)
        }
        flush()
        return out.sortedBy { it.index }
    }
}
