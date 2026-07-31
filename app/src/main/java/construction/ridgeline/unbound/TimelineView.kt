package construction.ridgeline.unbound

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * A single-day hour-by-hour timeline drawn on a Canvas. Timed events are laid out
 * in columns (via TimelineLayout) so overlaps sit side by side. Tap an event to
 * edit it; long-press and drag on empty space to create a timed event.
 *
 * Meant to live inside a vertical ScrollView: it consumes touches, and only once a
 * long-press begins does it ask the parent to stop intercepting so a create-drag
 * isn't stolen by scrolling.
 */
class TimelineView(context: Context) : View(context) {

    var onEventTap: (Ev) -> Unit = {}
    var onCreateRange: (startMs: Long, endMs: Long) -> Unit = { _, _ -> }

    private val den = resources.displayMetrics.density
    private val hourH = 60f * den
    private val gutter = 52f * den
    private var pal: WeekRenderer.Palette = WeekRenderer.LIGHT
    private var date: LocalDate = LocalDate.now()
    private var events: List<Ev> = emptyList()
    private var placements: Map<Long, TimelineLayout.Placement> = emptyMap()

    private var dragging = false
    private var dragStartMin = 0
    private var dragEndMin = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * den; textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val evText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * den }
    private val evTime = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * den; typeface = Typeface.DEFAULT_BOLD
    }

    fun setData(date: LocalDate, all: List<Ev>, palette: WeekRenderer.Palette) {
        this.date = date
        this.pal = palette
        val zone = ZoneId.systemDefault()
        // timed events that start on this day
        events = all.filter { !it.allDay && Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate() == date }
            .sortedBy { it.begin }
        val spans = events.map {
            val s = Instant.ofEpochMilli(it.begin).atZone(zone)
            val e = Instant.ofEpochMilli(it.end).atZone(zone)
            val startMin = s.hour * 60 + s.minute
            val endMin = max(startMin + 20, min(24 * 60, e.toLocalDate().let { d ->
                if (d.isAfter(date)) 24 * 60 else e.hour * 60 + e.minute
            }))
            TimelineLayout.Span(events.indexOf(it), startMin, endMin)
        }
        placements = TimelineLayout.place(spans).associateBy { events[it.index].id }
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (24 * hourH).toInt())
    }

    private fun yToMin(y: Float): Int = ((y / hourH) * 60f).toInt().coerceIn(0, 24 * 60)
    private fun minToY(m: Int): Float = m / 60f * hourH
    private fun minToMs(m: Int): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + m * 60_000L

    override fun onDraw(canvas: Canvas) {
        val trackLeft = gutter
        val trackW = width - gutter - 4f * den

        // hour grid
        linePaint.color = pal.line
        for (h in 0..24) {
            val y = h * hourH
            canvas.drawLine(gutter, y, width.toFloat(), y, linePaint)
            if (h < 24) {
                labelPaint.color = pal.faint
                val label = when {
                    h == 0 -> "12a"; h < 12 -> "${h}a"; h == 12 -> "12p"; else -> "${h - 12}p"
                }
                canvas.drawText(label, gutter - 6f * den, y + 12f * den, labelPaint)
            }
        }

        // current-time line
        if (date == LocalDate.now()) {
            val now = java.time.LocalTime.now()
            val y = minToY(now.hour * 60 + now.minute)
            linePaint.color = pal.todayPill
            canvas.drawCircle(gutter, y, 3f * den, linePaint)
            canvas.drawLine(gutter, y, width.toFloat(), y, linePaint)
        }

        // events
        val zone = ZoneId.systemDefault()
        for (ev in events) {
            val p = placements[ev.id] ?: continue
            val s = Instant.ofEpochMilli(ev.begin).atZone(zone)
            val e = Instant.ofEpochMilli(ev.end).atZone(zone)
            val startMin = s.hour * 60 + s.minute
            val endMin = max(startMin + 20, if (e.toLocalDate().isAfter(date)) 24 * 60 else e.hour * 60 + e.minute)
            val colW = trackW / p.cols
            val left = trackLeft + p.col * colW
            val top = minToY(startMin)
            val bottom = minToY(endMin)
            val rect = RectF(left + 1f * den, top + 1f * den, left + colW - 1f * den, bottom - 1f * den)
            val color = if (ev.color == 0) pal.defaultEv else ev.color
            canvas.drawRoundRect(rect, 6f * den, 6f * den, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (0x33 shl 24) or (color and 0x00FFFFFF)
            })
            canvas.drawRect(rect.left, rect.top, rect.left + 3f * den, rect.bottom, Paint().apply { this.color = color })
            // text
            val tx = rect.left + 7f * den
            val avail = (rect.width() - 9f * den).coerceAtLeast(1f)
            evTime.color = if (pal.dark) WeekRenderer.lighten(color, 0.5f) else WeekRenderer.darken(color, 0.4f)
            canvas.drawText(fmt(s.hour, s.minute), tx, rect.top + 13f * den, evTime)
            evText.color = pal.ink
            if (rect.height() > 26f * den) {
                val title = TextUtils.ellipsize(if (ev.title.isBlank()) "(no title)" else ev.title, evText, avail, TextUtils.TruncateAt.END)
                canvas.drawText(title, 0, title.length, tx, rect.top + 27f * den, evText)
            }
        }

        // drag-to-create overlay
        if (dragging) {
            val a = min(dragStartMin, dragEndMin)
            val b = max(dragStartMin, dragEndMin)
            val rect = RectF(gutter, minToY(a), width.toFloat(), minToY(max(b, a + 15)))
            canvas.drawRoundRect(rect, 6f * den, 6f * den, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x55 shl 24) or (pal.todayPill and 0x00FFFFFF)
            })
        }
    }

    private fun fmt(h: Int, m: Int): String {
        val ap = if (h < 12) "a" else "p"
        var hh = h % 12; if (hh == 0) hh = 12
        return if (m == 0) "$hh$ap" else "$hh:${m.toString().padStart(2, '0')}$ap"
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { onEventTap(it); return true }
            return false
        }
        override fun onLongPress(e: MotionEvent) {
            dragging = true
            dragStartMin = (yToMin(e.y) / 15) * 15
            dragEndMin = dragStartMin + 60
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    })

    private fun hitTest(x: Float, y: Float): Ev? {
        val zone = ZoneId.systemDefault()
        val trackLeft = gutter
        val trackW = width - gutter - 4f * den
        for (ev in events) {
            val p = placements[ev.id] ?: continue
            val s = Instant.ofEpochMilli(ev.begin).atZone(zone)
            val e = Instant.ofEpochMilli(ev.end).atZone(zone)
            val startMin = s.hour * 60 + s.minute
            val endMin = max(startMin + 20, if (e.toLocalDate().isAfter(date)) 24 * 60 else e.hour * 60 + e.minute)
            val colW = trackW / p.cols
            val left = trackLeft + p.col * colW
            if (x in left..(left + colW) && y in minToY(startMin)..minToY(endMin)) return ev
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) { dragEndMin = (yToMin(e.y) / 15) * 15; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val a = min(dragStartMin, dragEndMin)
                    val b = max(dragStartMin, dragEndMin).let { if (it - a < 15) a + 60 else it }
                    onCreateRange(minToMs(a), minToMs(b))
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun endDrag() {
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }
}
