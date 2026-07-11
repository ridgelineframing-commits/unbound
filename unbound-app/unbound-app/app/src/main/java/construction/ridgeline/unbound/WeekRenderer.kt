package construction.ridgeline.unbound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object WeekRenderer {

    /** Theme palette for everything drawn on the canvas. */
    data class Palette(
        val ink: Int,            // primary text
        val stone: Int,          // secondary text
        val faint: Int,          // tertiary text / labels
        val line: Int,           // grid hairlines
        val defaultEv: Int,      // fallback event color
        val timeText: Int,       // timed-event time stamp
        val todayPill: Int,      // background of today's date pill
        val todayPillText: Int,
        val todayTint: Int,      // wash over today's column
        val weekendTint: Int,    // wash over Sat/Sun columns
        val pastText: Int,       // dimmed date number for past days
        val strike: Int,         // slash across finished days
        val dark: Boolean
    )

    val LIGHT = Palette(
        ink = 0xFF1B1B19.toInt(),
        stone = 0xFF57534E.toInt(),
        faint = 0xFFA8A29E.toInt(),
        line = 0x14000000,
        defaultEv = 0xFF71717A.toInt(),
        timeText = 0xFF8A857E.toInt(),
        todayPill = 0xFF1B1B19.toInt(),
        todayPillText = 0xFFFFFFFF.toInt(),
        todayTint = 0x0A1B4ED8,
        weekendTint = 0x05000000,
        pastText = 0xFFC9C5BF.toInt(),
        strike = 0x2E57534E,
        dark = false
    )

    val DARK = Palette(
        ink = 0xFFE9E7E2.toInt(),
        stone = 0xFFB0ACA5.toInt(),
        faint = 0xFF6E6B65.toInt(),
        line = 0x17FFFFFF,
        defaultEv = 0xFF8B8B93.toInt(),
        timeText = 0xFF807C74.toInt(),
        todayPill = 0xFFE9E7E2.toInt(),
        todayPillText = 0xFF16150F.toInt(),
        todayTint = 0x14BFD3FF,
        weekendTint = 0x06FFFFFF,
        pastText = 0xFF56534E.toInt(),
        strike = 0x30B0ACA5,
        dark = true
    )

    private const val MAX_BYTES = 3_500_000

    private data class Seg(
        val ev: Ev, val cs: Int, val ce: Int, val rs: Boolean, val re: Boolean,
        var lines: List<CharSequence> = emptyList()
    )

    private data class TimedRow(val ev: Ev, val lines: List<CharSequence>, val timeLabel: String)

    /**
     * Renders one week strip.
     *
     * @param minHeightPx if the week's natural content height is smaller, the cell
     *   grid is stretched so the widget always fills its allotted space.
     */
    fun renderWeek(
        ctx: Context,
        widthPx: Int,
        minHeightPx: Int,
        weekStart: LocalDate,
        today: LocalDate,
        all: List<Ev>,
        pal: Palette,
        textScale: Float,
        strikePast: Boolean,
        isFirstWeek: Boolean
    ): Bitmap {
        val den = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * den
        fun ts(v: Float) = v * den * textScale
        val zone = ZoneId.systemDefault()
        val w = widthPx.coerceIn(240, 1600)
        val colW = w / 7f
        val dates = (0..6).map { weekStart.plusDays(it.toLong()) }
        val weekEnd = dates[6]

        // ---- bucket events -------------------------------------------------
        val segs = ArrayList<Seg>()
        val timed = Array(7) { ArrayList<Ev>() }
        for (ev in all) {
            if (ev.allDay) {
                val s = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val eLast = Instant.ofEpochMilli(ev.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                if (eLast.isBefore(weekStart) || s.isAfter(weekEnd)) continue
                val sClamp = if (s.isBefore(weekStart)) weekStart else s
                val eClamp = if (eLast.isAfter(weekEnd)) weekEnd else eLast
                val cs = ChronoUnit.DAYS.between(weekStart, sClamp).toInt().coerceIn(0, 6)
                val ce = ChronoUnit.DAYS.between(weekStart, eClamp).toInt().coerceIn(0, 6)
                segs.add(Seg(ev, cs, ce, !s.isBefore(weekStart), !eLast.isAfter(weekEnd)))
            } else {
                val date = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalDate()
                val idx = ChronoUnit.DAYS.between(weekStart, date).toInt()
                if (idx in 0..6) timed[idx].add(ev)
            }
        }

        // ---- greedy lane packing for all-day bars ---------------------------
        val sorted = segs.sortedWith(compareBy({ it.cs }, { -(it.ce - it.cs) }))
        val lanes = ArrayList<ArrayList<Seg>>()
        for (s in sorted) {
            var placed = false
            for (lane in lanes) {
                if (lane.all { s.ce < it.cs || s.cs > it.ce }) {
                    lane.add(s); placed = true; break
                }
            }
            if (!placed) lanes.add(arrayListOf(s))
        }

        // ---- paints ---------------------------------------------------------
        val barText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val tPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ts(10.5f) }
        val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(10.5f)
            typeface = Typeface.MONOSPACE
            color = pal.timeText
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val monPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(9f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        // ---- measure: wrap titles up to 2 lines ------------------------------
        val barLineH = ts(14f)
        val barPadV = ts(6f)
        for (lane in lanes) for (s in lane) {
            if (s.rs || s.cs == 0) {
                val x0 = s.cs * colW + dp(2f)
                val x1 = (s.ce + 1) * colW - dp(2f)
                val avail = x1 - x0 - dp(10f) - dp(6f)
                s.lines = if (avail > 0) wrapTwo(s.ev.title, barText, avail, avail) else emptyList()
            }
        }
        val laneHeights = lanes.map { lane ->
            val maxLines = lane.maxOf { if (it.lines.size > 1) 2 else 1 }
            maxLines * barLineH + barPadV
        }

        val timeIndent = dp(8f)
        val dotSpace = dp(10f)
        val lineH = ts(14.5f)
        val timedRows = Array(7) { ArrayList<TimedRow>() }
        for (i in 0..6) {
            for (ev in timed[i]) {
                val lt = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalTime()
                val timeLabel = fmtTime(lt.hour, lt.minute) + " "
                val tw = mPaint.measureText(timeLabel)
                val w1 = colW - dotSpace - timeIndent - tw - dp(4f)
                val w2 = colW - dotSpace - timeIndent - dp(4f)
                val lines =
                    if (w1 > 0) wrapTwo(ev.title, tPaint, w1, w2)
                    else listOf(TextUtils.ellipsize(ev.title, tPaint, (colW - dotSpace).coerceAtLeast(1f), TextUtils.TruncateAt.END))
                timedRows[i].add(TimedRow(ev, lines, timeLabel))
            }
        }
        val timedColH = (0..6).map { d -> timedRows[d].sumOf { (it.lines.size * lineH).toDouble() }.toFloat() }
        val timedBlock = timedColH.maxOrNull() ?: 0f

        // ---- natural height, then stretch to fill allotted space ------------
        val padTop = dp(6f)
        val padBottom = dp(8f)
        val numH = ts(24f)
        val laneGap = dp(3f)
        val laneBlock = laneHeights.sum() + lanes.size * laneGap
        val timedGap = if (timedBlock > 0f) dp(3f) else 0f
        var height = (padTop + numH + laneBlock + timedGap + timedBlock + padBottom).toInt()
        val minH = maxOf(dp(58f).toInt(), minHeightPx)
        if (height < minH) height = minH

        var bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)

        // ---- column washes ---------------------------------------------------
        val wash = Paint()
        for (i in 0..6) {
            val d = dates[i]
            val x0 = i * colW
            if (d.dayOfWeek.value >= 6) { // Sat / Sun
                wash.color = pal.weekendTint
                cv.drawRect(x0, 0f, x0 + colW, height.toFloat(), wash)
            }
            if (d == today) {
                wash.color = pal.todayTint
                cv.drawRect(x0, 0f, x0 + colW, height.toFloat(), wash)
            }
        }

        // ---- grid -------------------------------------------------------------
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = pal.line
        p.strokeWidth = if (den * 0.5f < 1f) 1f else den * 0.5f
        for (i in 1..6) cv.drawLine(i * colW, 0f, i * colW, height.toFloat(), p)
        if (!isFirstWeek) cv.drawLine(0f, 0f, w.toFloat(), 0f, p)

        // ---- strike through finished days -------------------------------------
        if (strikePast) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.strike
                strokeWidth = dp(2f)
                strokeCap = Paint.Cap.ROUND
            }
            val inset = dp(7f)
            for (i in 0..6) {
                if (dates[i].isBefore(today)) {
                    val x0 = i * colW
                    cv.drawLine(
                        x0 + inset, height - inset,
                        x0 + colW - inset, inset, sp
                    )
                }
            }
        }

        // ---- date numbers (+ month marker on the 1st) --------------------------
        for (i in 0..6) {
            val date = dates[i]
            val isToday = date == today
            val isPast = date.isBefore(today)
            val cx = i * colW + dp(8f)
            if (isToday) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.todayPill }
                val pillW = ts(22f)
                val rectF = RectF(cx - dp(2f), padTop, cx - dp(2f) + pillW, padTop + ts(20f))
                cv.drawRoundRect(rectF, ts(10f), ts(10f), bgPaint)
                numPaint.color = pal.todayPillText
            } else {
                numPaint.color = if (isPast) pal.pastText else pal.stone
            }
            val fm = numPaint.fontMetrics
            val ty = padTop + (ts(20f) - (fm.descent - fm.ascent)) / 2 - fm.ascent
            val numStr = date.dayOfMonth.toString()
            cv.drawText(numStr, cx + dp(2f), ty, numPaint)

            if (date.dayOfMonth == 1) {
                monPaint.color = if (isPast) pal.pastText else pal.ink
                val mon = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    .uppercase(Locale.getDefault())
                val nx = cx + dp(2f) + numPaint.measureText(numStr) + dp(4f)
                cv.drawText(mon, nx, ty - ts(1f), monPaint)
            }
        }

        // ---- all-day bars -------------------------------------------------------
        var y = padTop + numH
        for ((li, lane) in lanes.withIndex()) {
            val laneH = laneHeights[li]
            for (s in lane) {
                val c = if (s.ev.color == 0) pal.defaultEv else s.ev.color
                val x0 = s.cs * colW + dp(2f)
                val x1 = (s.ce + 1) * colW - dp(2f)
                val rectF = RectF(x0, y, x1, y + laneH)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(c, if (pal.dark) 0x30 else 0x24)
                }
                val rl = if (s.rs) dp(5f) else 0f
                val rr = if (s.re) dp(5f) else 0f
                drawRoundRectSides(cv, rectF, rl, rr, bg)
                if (s.rs) {
                    val cap = Paint().apply { color = c }
                    cv.drawRect(x0, y, x0 + dp(3f), y + laneH, cap)
                }
                if (s.lines.isNotEmpty()) {
                    barText.color = if (pal.dark) lighten(c, 0.45f) else darken(c, 0.45f)
                    val fm = barText.fontMetrics
                    val blockH = s.lines.size * barLineH
                    var ly = y + (laneH - blockH) / 2f
                    for (line in s.lines) {
                        val base = ly + (barLineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
                        cv.drawText(line, 0, line.length, x0 + dp(8f), base, barText)
                        ly += barLineH
                    }
                }
            }
            y += laneH + laneGap
        }

        // ---- timed events ---------------------------------------------------------
        if (timedBlock > 0f) {
            y += timedGap
            for (i in 0..6) {
                var ty = y
                for (row in timedRows[i]) {
                    val ev = row.ev
                    val c = if (ev.color == 0) pal.defaultEv else ev.color
                    val cx = i * colW + dp(6f)
                    val firstMidY = ty + lineH / 2f
                    val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
                    cv.drawCircle(cx + dp(2f), firstMidY, ts(2.5f), dotP)
                    val tw = mPaint.measureText(row.timeLabel)
                    val fmm = tPaint.fontMetrics
                    tPaint.color = if (dates[i].isBefore(today)) pal.faint else pal.ink
                    for ((li, line) in row.lines.withIndex()) {
                        val baseline = ty + li * lineH + lineH / 2f - (fmm.ascent + fmm.descent) / 2
                        if (li == 0) {
                            cv.drawText(row.timeLabel, cx + timeIndent, baseline, mPaint)
                            cv.drawText(line, 0, line.length, cx + timeIndent + tw, baseline, tPaint)
                        } else {
                            cv.drawText(line, 0, line.length, cx + timeIndent, baseline, tPaint)
                        }
                    }
                    ty += row.lines.size * lineH
                }
            }
        }

        // ---- keep bitmap under RemoteViews-friendly size ----------------------------
        val bytes = w * height * 4
        if (bytes > MAX_BYTES) {
            val s = Math.sqrt(MAX_BYTES.toDouble() / bytes).toFloat()
            val nw = (w * s).toInt().coerceAtLeast(1)
            val nh = (height * s).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    /** Break text into at most two lines; the second line is ellipsized. */
    private fun wrapTwo(text: String, paint: TextPaint, w1: Float, w2: Float): List<CharSequence> {
        if (paint.measureText(text) <= w1) return listOf(text)
        var n = paint.breakText(text, true, w1, null)
        if (n <= 0) n = 1
        val cut = text.lastIndexOf(' ', n - 1)
        if (cut > 0 && cut >= n / 2) n = cut
        val line1 = text.substring(0, n).trimEnd()
        val rest = text.substring(n).trimStart()
        if (rest.isEmpty()) return listOf(line1)
        val line2 = TextUtils.ellipsize(rest, paint, w2.coerceAtLeast(1f), TextUtils.TruncateAt.END)
        return listOf(line1, line2)
    }

    private fun fmtTime(h: Int, m: Int): String {
        val ap = if (h < 12) "a" else "p"
        var hh = h % 12
        if (hh == 0) hh = 12
        return "$hh:${m.toString().padStart(2, '0')}$ap"
    }

    private fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0x00FFFFFF)

    private fun darken(c: Int, f: Float): Int {
        val r = (((c shr 16) and 0xFF) * (1 - f)).toInt()
        val g = (((c shr 8) and 0xFF) * (1 - f)).toInt()
        val b = ((c and 0xFF) * (1 - f)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lighten(c: Int, f: Float): Int {
        val r = ((c shr 16) and 0xFF)
        val g = ((c shr 8) and 0xFF)
        val b = (c and 0xFF)
        val nr = (r + ((255 - r) * f)).toInt().coerceAtMost(255)
        val ng = (g + ((255 - g) * f)).toInt().coerceAtMost(255)
        val nb = (b + ((255 - b) * f)).toInt().coerceAtMost(255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun drawRoundRectSides(cv: Canvas, r: RectF, rl: Float, rr: Float, p: Paint) {
        if (rl == 0f && rr == 0f) {
            cv.drawRect(r, p)
            return
        }
        val path = Path()
        val radii = floatArrayOf(rl, rl, rr, rr, rr, rr, rl, rl)
        path.addRoundRect(r, radii, Path.Direction.CW)
        cv.drawPath(path, p)
    }
}
