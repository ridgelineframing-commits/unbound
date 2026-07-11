package construction.ridgeline.unbound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object WeekRenderer {
    private const val PAPER = 0xFFF4F4F1.toInt()
    private const val INK = 0xFF1B1B19.toInt()
    private const val STONE = 0xFF57534E.toInt()
    private const val LINE = 0x14000000
    private const val DEFAULT = 0xFF71717A.toInt()
    private const val MAX_BYTES = 2_500_000

    private data class Seg(val ev: Ev, val cs: Int, val ce: Int, val rs: Boolean, val re: Boolean)

    fun renderWeek(ctx: Context, widthPx: Int, weekStart: LocalDate, today: LocalDate, all: List<Ev>): Bitmap {
        val den = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * den
        val zone = ZoneId.systemDefault()
        val w = widthPx.coerceIn(240, 1400)
        val colW = w / 7f
        val dates = (0..6).map { weekStart.plusDays(it.toLong()) }
        val weekEnd = dates[6]

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

        // greedy lane packing
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
        val timedMax = (0..6).maxOf { timed[it].size }

        val padTop = dp(6f)
        val padBottom = dp(8f)
        val numH = dp(24f)
        val laneH = dp(20f)
        val laneGap = dp(3f)
        val lineH = dp(15f)
        val laneBlock = lanes.size * (laneH + laneGap)
        val timedGap = if (timedMax > 0) dp(3f) else 0f
        val timedBlock = timedMax * lineH
        var height = (padTop + numH + laneBlock + timedGap + timedBlock + padBottom).toInt()
        if (height < dp(58f).toInt()) height = dp(58f).toInt()

        var bmp = Bitmap.createBitmap(w, height, Bitmap.Config.RGB_565)
        val cv = Canvas(bmp)
        cv.drawColor(PAPER)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = LINE
        p.strokeWidth = if (den * 0.5f < 1f) 1f else den * 0.5f
        for (i in 1..6) cv.drawLine(i * colW, 0f, i * colW, height.toFloat(), p)

        // date numbers
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        for (i in 0..6) {
            val date = dates[i]
            val isToday = date == today
            val cx = i * colW + dp(8f)
            if (isToday) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
                val rectF = RectF(cx - dp(2f), padTop, cx - dp(2f) + dp(22f), padTop + dp(20f))
                cv.drawRoundRect(rectF, dp(6f), dp(6f), bgPaint)
                numPaint.color = 0xFFFFFFFF.toInt()
            } else {
                numPaint.color = STONE
            }
            val fm = numPaint.fontMetrics
            val ty = padTop + (dp(20f) - (fm.descent - fm.ascent)) / 2 - fm.ascent
            cv.drawText(date.dayOfMonth.toString(), cx + dp(2f), ty, numPaint)
        }

        // phase bars
        var y = padTop + numH
        val barText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        for (lane in lanes) {
            for (s in lane) {
                val c = if (s.ev.color == 0) DEFAULT else s.ev.color
                val x0 = s.cs * colW + dp(2f)
                val x1 = (s.ce + 1) * colW - dp(2f)
                val rectF = RectF(x0, y, x1, y + laneH)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(c, 0x24) }
                val rl = if (s.rs) dp(5f) else 0f
                val rr = if (s.re) dp(5f) else 0f
                drawRoundRectSides(cv, rectF, rl, rr, bg)
                if (s.rs) {
                    val cap = Paint().apply { color = c }
                    cv.drawRect(x0, y, x0 + dp(3f), y + laneH, cap)
                }
                if (s.rs || s.cs == 0) {
                    barText.color = darken(c, 0.45f)
                    val avail = (x1 - x0 - dp(10f))
                    if (avail > 0) {
                        val label = TextUtils.ellipsize(s.ev.title, barText, avail, TextUtils.TruncateAt.END)
                        val fm = barText.fontMetrics
                        val ty = y + (laneH - (fm.descent - fm.ascent)) / 2 - fm.ascent
                        cv.drawText(label, 0, label.length, x0 + dp(8f), ty, barText)
                    }
                }
            }
            y += laneH + laneGap
        }

        // timed events
        if (timedMax > 0) {
            y += timedGap
            val tPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10.5f) }
            val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(10.5f)
                typeface = Typeface.MONOSPACE
                color = 0xFF8A857E.toInt()
            }
            for (i in 0..6) {
                var ty = y
                for (ev in timed[i]) {
                    val c = if (ev.color == 0) DEFAULT else ev.color
                    val cx = i * colW + dp(6f)
                    val midY = ty + lineH / 2f
                    val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
                    cv.drawCircle(cx + dp(2f), midY, dp(2.5f), dotP)
                    val lt = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalTime()
                    val timeLabel = fmtTime(lt.hour, lt.minute) + " "
                    val tw = mPaint.measureText(timeLabel)
                    val fmm = tPaint.fontMetrics
                    val baseline = midY - (fmm.ascent + fmm.descent) / 2
                    cv.drawText(timeLabel, cx + dp(8f), baseline, mPaint)
                    tPaint.color = INK
                    val avail = colW - dp(10f) - tw - dp(8f)
                    if (avail > 0) {
                        val label = TextUtils.ellipsize(ev.title, tPaint, avail, TextUtils.TruncateAt.END)
                        cv.drawText(label, 0, label.length, cx + dp(8f) + tw, baseline, tPaint)
                    }
                    ty += lineH
                }
            }
        }

        val bytes = w * height * 2
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
