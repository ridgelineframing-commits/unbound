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

    // "Float" theme — cool ink, mint accent, chip-style events.
    val LIGHT = Palette(
        ink = 0xFF1B1E24.toInt(),
        stone = 0xFF4A4F58.toInt(),
        faint = 0xFF9AA0AA.toInt(),
        line = 0x121B1E24,
        defaultEv = 0xFF6E7683.toInt(),
        timeText = 0xFF6E7683.toInt(),
        todayPill = 0xFF1E7A5A.toInt(),
        todayPillText = 0xFFF4F5F7.toInt(),
        todayTint = 0x0F1E7A5A,
        weekendTint = 0x05000000,
        pastText = 0xFFC3C7CE.toInt(),
        strike = 0x2E4A4F58,
        dark = false
    )

    val DARK = Palette(
        ink = 0xFFF4F5F7.toInt(),
        stone = 0xFFAEB4BF.toInt(),
        faint = 0xFF7C828E.toInt(),
        line = 0x14FFFFFF,
        defaultEv = 0xFF8B94A6.toInt(),
        timeText = 0xFF8B94A6.toInt(),
        todayPill = 0xFF8FE3C0.toInt(),
        todayPillText = 0xFF101318.toInt(),
        todayTint = 0x128FE3C0.toInt(),
        weekendTint = 0x05FFFFFF,
        pastText = 0xFF565B66.toInt(),
        strike = 0x30AEB4BF,
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
            textSize = ts(10.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val tPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(10.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(8.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        // Timed events render as tinted chips: bold time line, then the wrapped title.
        val chipPadH = dp(5f)
        val chipPadV = dp(3.5f)
        val chipGap = dp(3f)
        val chipMargin = dp(2f)
        val timeLineH = ts(11f)
        val lineH = ts(13.5f)
        val titleW = (colW - 2 * chipMargin - 2 * chipPadH).coerceAtLeast(1f)
        val timedRows = Array(7) { ArrayList<TimedRow>() }
        for (i in 0..6) {
            for (ev in timed[i]) {
                val lt = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalTime()
                val timeLabel = fmtTime(lt.hour, lt.minute)
                val lines = wrapTwo(ev.title, tPaint, titleW, titleW)
                timedRows[i].add(TimedRow(ev, lines, timeLabel))
            }
        }
        fun chipH(r: TimedRow) = 2 * chipPadV + timeLineH + r.lines.size * lineH
        val timedColH = (0..6).map { d -> timedRows[d].sumOf { (chipH(it) + chipGap).toDouble() }.toFloat() }
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

        // ---- date numbers: centered; today = filled accent circle;
        //      month turn shows "AUG 1" in the accent color;
        //      finished days are dimmed and struck through their number ----------
        for (i in 0..6) {
            cv.save()
            cv.clipRect(i * colW, 0f, (i + 1) * colW, padTop + numH)
            val date = dates[i]
            val isToday = date == today
            val isPast = date.isBefore(today)
            val cxMid = i * colW + colW / 2f
            val numStr = if (date.dayOfMonth == 1 && !isToday)
                date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    .uppercase(Locale.getDefault()) + " 1"
            else date.dayOfMonth.toString()
            if (isToday) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.todayPill }
                cv.drawCircle(cxMid, padTop + ts(10f), ts(10.5f), bgPaint)
                numPaint.color = pal.todayPillText
            } else if (date.dayOfMonth == 1) {
                numPaint.color = if (isPast) pal.pastText else pal.todayPill
            } else {
                numPaint.color = if (isPast) pal.pastText else pal.ink
            }
            val fm = numPaint.fontMetrics
            val ty = padTop + (ts(20f) - (fm.descent - fm.ascent)) / 2 - fm.ascent
            val tw = numPaint.measureText(numStr)
            cv.drawText(numStr, cxMid - tw / 2f, ty, numPaint)
            if (strikePast && isPast) {
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = pal.pastText
                    strokeWidth = dp(1.2f)
                    strokeCap = Paint.Cap.ROUND
                }
                val midY = padTop + ts(10f)
                cv.drawLine(cxMid - tw / 2f - dp(2f), midY, cxMid + tw / 2f + dp(2f), midY, sp)
            }
            cv.restore()
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
                // solid saturated banner with tone-matched text
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
                val rl = if (s.rs) dp(6f) else 0f
                val rr = if (s.re) dp(6f) else 0f
                drawRoundRectSides(cv, rectF, rl, rr, bg)
                if (s.lines.isNotEmpty()) {
                    barText.color = if (pal.dark) darken(c, 0.78f) else lighten(c, 0.88f)
                    val fm = barText.fontMetrics
                    val blockH = s.lines.size * barLineH
                    var ly = y + (laneH - blockH) / 2f
                    cv.save()
                    cv.clipRect(rectF) // labels can never spill past the bar
                    for (line in s.lines) {
                        val base = ly + (barLineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
                        cv.drawText(line, 0, line.length, x0 + dp(8f), base, barText)
                        ly += barLineH
                    }
                    cv.restore()
                }
            }
            y += laneH + laneGap
        }

        // ---- timed events: tinted chips -------------------------------------------
        if (timedBlock > 0f) {
            y += timedGap
            for (i in 0..6) {
                var ty = y
                val isPastDay = dates[i].isBefore(today)
                cv.save()
                cv.clipRect(i * colW, y, (i + 1) * colW, height.toFloat()) // no cross-column bleed
                for (row in timedRows[i]) {
                    val c = if (row.ev.color == 0) pal.defaultEv else row.ev.color
                    val h = chipH(row)
                    val x0 = i * colW + chipMargin
                    val x1 = (i + 1) * colW - chipMargin
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = withAlpha(c, if (pal.dark) 0x30 else 0x22)
                    }
                    cv.drawRoundRect(RectF(x0, ty, x1, ty + h), dp(6f), dp(6f), bg)
                    // bold time in the event's color
                    mPaint.color = if (isPastDay) pal.pastText
                        else if (pal.dark) lighten(c, 0.55f) else darken(c, 0.45f)
                    val fmT = mPaint.fontMetrics
                    val tBase = ty + chipPadV + (timeLineH - (fmT.descent - fmT.ascent)) / 2 - fmT.ascent
                    cv.drawText(row.timeLabel, x0 + chipPadH, tBase, mPaint)
                    // title below, full wrap
                    tPaint.color = if (isPastDay) pal.faint else pal.ink
                    val fmm = tPaint.fontMetrics
                    var ly = ty + chipPadV + timeLineH
                    for (line in row.lines) {
                        val base = ly + (lineH - (fmm.descent - fmm.ascent)) / 2 - fmm.ascent
                        cv.drawText(line, 0, line.length, x0 + chipPadH, base, tPaint)
                        ly += lineH
                    }
                    ty += h + chipGap
                }
                cv.restore()
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

    fun darken(c: Int, f: Float): Int {
        val r = (((c shr 16) and 0xFF) * (1 - f)).toInt()
        val g = (((c shr 8) and 0xFF) * (1 - f)).toInt()
        val b = ((c and 0xFF) * (1 - f)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun lighten(c: Int, f: Float): Int {
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
