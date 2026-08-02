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
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Hour-by-hour timeline for one or more day columns, drawn on a Canvas. One column
 * is the Day view; seven columns is the Week view. Timed events are laid out in
 * sub-columns per day (via TimelineLayout) so overlaps sit side by side. Tap an
 * event to edit; long-press and drag on empty time to create a timed event on that
 * day. Meant to live in a vertical ScrollView (the day header lives above it).
 */
class TimelineView(context: Context) : View(context) {

    var onEventTap: (Ev) -> Unit = {}
    var onCreateRange: (startMs: Long, endMs: Long) -> Unit = { _, _ -> }

    private val den = resources.displayMetrics.density
    private val hourH = 56f * den
    private val gutter = 46f * den
    private var pal: WeekRenderer.Palette = WeekRenderer.LIGHT
    private var dates: List<LocalDate> = listOf(LocalDate.now())
    private var eventsByDay: Map<LocalDate, List<Ev>> = emptyMap()
    private var placeByDay: Map<LocalDate, Map<Long, TimelineLayout.Placement>> = emptyMap()

    private var dragging = false
    private var dragCol = 0
    private var dragStartMin = 0
    private var dragEndMin = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * den; textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val evText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * den }
    private val evTime = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * den; typeface = Typeface.DEFAULT_BOLD
    }

    fun setData(date: LocalDate, all: List<Ev>, palette: WeekRenderer.Palette) =
        setData(listOf(date), all, palette)

    fun setData(days: List<LocalDate>, all: List<Ev>, palette: WeekRenderer.Palette) {
        dates = if (days.isEmpty()) listOf(LocalDate.now()) else days
        pal = palette
        val zone = ZoneId.systemDefault()
        val eb = HashMap<LocalDate, List<Ev>>()
        val pb = HashMap<LocalDate, Map<Long, TimelineLayout.Placement>>()
        for (d in dates) {
            val dayEvents = all
                .filter { !it.allDay && Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate() == d }
                .sortedBy { it.begin }
            eb[d] = dayEvents
            val spans = dayEvents.mapIndexed { i, ev ->
                val s = Instant.ofEpochMilli(ev.begin).atZone(zone)
                val e = Instant.ofEpochMilli(ev.end).atZone(zone)
                val startMin = s.hour * 60 + s.minute
                val endMin = max(startMin + 20, if (e.toLocalDate().isAfter(d)) 24 * 60 else e.hour * 60 + e.minute)
                TimelineLayout.Span(i, startMin, endMin)
            }
            pb[d] = TimelineLayout.place(spans).associateBy { dayEvents[it.index].id }
        }
        eventsByDay = eb
        placeByDay = pb
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (24 * hourH).toInt())
    }

    private fun colWidth() = (width - gutter) / dates.size
    private fun yToMin(y: Float): Int = ((y / hourH) * 60f).toInt().coerceIn(0, 24 * 60)
    private fun minToY(m: Int): Float = m / 60f * hourH
    private fun minToMs(date: LocalDate, m: Int): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + m * 60_000L

    override fun onDraw(canvas: Canvas) {
        val colW = colWidth()
        val today = LocalDate.now()

        // hour grid + labels
        for (h in 0..24) {
            val y = h * hourH
            linePaint.color = pal.line
            canvas.drawLine(gutter, y, width.toFloat(), y, linePaint)
            if (h < 24) {
                labelPaint.color = pal.faint
                val label = when { h == 0 -> "12a"; h < 12 -> "${h}a"; h == 12 -> "12p"; else -> "${h - 12}p" }
                canvas.drawText(label, gutter - 5f * den, y + 12f * den, labelPaint)
            }
        }

        for ((c, date) in dates.withIndex()) {
            val colLeft = gutter + c * colW
            if (c > 0) { // day separator
                linePaint.color = pal.line
                canvas.drawLine(colLeft, 0f, colLeft, 24 * hourH, linePaint)
            }
            drawColumn(canvas, date, colLeft, colW, date == today)
        }

        if (dragging && dragCol < dates.size) {
            val colLeft = gutter + dragCol * colW
            val a = min(dragStartMin, dragEndMin)
            val b = max(dragStartMin, dragEndMin)
            val rect = RectF(colLeft, minToY(a), colLeft + colW, minToY(max(b, a + 15)))
            canvas.drawRoundRect(rect, 6f * den, 6f * den, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x55 shl 24) or (pal.todayPill and 0x00FFFFFF)
            })
        }
    }

    private fun drawColumn(canvas: Canvas, date: LocalDate, colLeft: Float, colW: Float, isToday: Boolean) {
        val zone = ZoneId.systemDefault()
        val events = eventsByDay[date] ?: emptyList()
        val places = placeByDay[date] ?: emptyMap()
        val single = dates.size == 1
        for (ev in events) {
            val p = places[ev.id] ?: continue
            val s = Instant.ofEpochMilli(ev.begin).atZone(zone)
            val e = Instant.ofEpochMilli(ev.end).atZone(zone)
            val startMin = s.hour * 60 + s.minute
            val endMin = max(startMin + 20, if (e.toLocalDate().isAfter(date)) 24 * 60 else e.hour * 60 + e.minute)
            val subW = colW / p.cols
            val left = colLeft + p.col * subW
            val rect = RectF(left + 1f * den, minToY(startMin) + 1f * den, left + subW - 1f * den, minToY(endMin) - 1f * den)
            val color = if (ev.color == 0) pal.defaultEv else ev.color
            canvas.drawRoundRect(rect, 5f * den, 5f * den, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (0x33 shl 24) or (color and 0x00FFFFFF)
            })
            canvas.drawRect(rect.left, rect.top, rect.left + 3f * den, rect.bottom, Paint().apply { this.color = color })
            val tx = rect.left + (if (single) 7f else 5f) * den
            val avail = (rect.width() - (if (single) 9f else 6f) * den).coerceAtLeast(1f)
            if (single) {
                evTime.color = if (pal.dark) WeekRenderer.lighten(color, 0.5f) else WeekRenderer.darken(color, 0.4f)
                canvas.drawText(fmt(s.hour, s.minute), tx, rect.top + 12f * den, evTime)
                if (rect.height() > 25f * den) {
                    evText.color = pal.ink
                    val title = TextUtils.ellipsize(if (ev.title.isBlank()) "(no title)" else ev.title, evText, avail, TextUtils.TruncateAt.END)
                    canvas.drawText(title, 0, title.length, tx, rect.top + 26f * den, evText)
                }
            } else if (rect.height() > 12f * den) {
                evText.color = pal.ink
                val title = TextUtils.ellipsize(if (ev.title.isBlank()) "•" else ev.title, evText, avail, TextUtils.TruncateAt.END)
                canvas.drawText(title, 0, title.length, tx, rect.top + 11f * den, evText)
            }
        }
        if (isToday) {
            val now = LocalTime.now()
            val y = minToY(now.hour * 60 + now.minute)
            linePaint.color = pal.todayPill
            canvas.drawCircle(colLeft + 3f * den, y, 3f * den, linePaint)
            canvas.drawLine(colLeft, y, colLeft + colW, y, linePaint)
        }
    }

    private fun fmt(h: Int, m: Int): String {
        val ap = if (h < 12) "a" else "p"
        var hh = h % 12; if (hh == 0) hh = 12
        return if (m == 0) "$hh$ap" else "$hh:${m.toString().padStart(2, '0')}$ap"
    }

    private fun colAt(x: Float): Int = ((x - gutter) / colWidth()).toInt().coerceIn(0, dates.size - 1)

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { onEventTap(it); return true }
            return false
        }
        override fun onLongPress(e: MotionEvent) {
            if (e.x < gutter) return
            dragging = true
            dragCol = colAt(e.x)
            dragStartMin = (yToMin(e.y) / 15) * 15
            dragEndMin = dragStartMin + 60
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    })

    private fun hitTest(x: Float, y: Float): Ev? {
        if (x < gutter) return null
        val col = colAt(x)
        val date = dates[col]
        val colLeft = gutter + col * colWidth()
        val colW = colWidth()
        val zone = ZoneId.systemDefault()
        val events = eventsByDay[date] ?: return null
        val places = placeByDay[date] ?: return null
        for (ev in events) {
            val p = places[ev.id] ?: continue
            val s = Instant.ofEpochMilli(ev.begin).atZone(zone)
            val e = Instant.ofEpochMilli(ev.end).atZone(zone)
            val startMin = s.hour * 60 + s.minute
            val endMin = max(startMin + 20, if (e.toLocalDate().isAfter(date)) 24 * 60 else e.hour * 60 + e.minute)
            val subW = colW / p.cols
            val left = colLeft + p.col * subW
            if (x in left..(left + subW) && y in minToY(startMin)..minToY(endMin)) return ev
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) { dragEndMin = (yToMin(e.y) / 15) * 15; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (dragging && dragCol < dates.size) {
                    val a = min(dragStartMin, dragEndMin)
                    val b = max(dragStartMin, dragEndMin).let { if (it - a < 15) a + 60 else it }
                    onCreateRange(minToMs(dates[dragCol], a), minToMs(dates[dragCol], b))
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
