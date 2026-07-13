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

    /** Theme palette for everything drawn on the canvas ("Float" 2c spec). */
    data class Palette(
        val ink: Int,            // primary text
        val stone: Int,          // secondary text
        val faint: Int,          // tertiary text / labels
        val line: Int,           // hairline dividers
        val defaultEv: Int,      // fallback event color
        val timeText: Int,       // timed-event time stamp fallback
        val todayPill: Int,      // accent: today circle, selection, toggles
        val todayPillText: Int,  // text on accent
        val todayTint: Int,      // (kept for agenda today wash)
        val weekendTint: Int,    // unused in 2c; kept for compatibility
        val pastText: Int,       // dimmed date number for past days
        val strike: Int,         // strike through finished day numbers
        val dark: Boolean
    )

    // ---- "Float" 2c tokens ----------------------------------------------------
    val LIGHT = Palette(
        ink = 0xFF1B1E24.toInt(),
        stone = 0xFF4A4F58.toInt(),
        faint = 0x731B1E24,          // rgba(27,30,36,.45)
        line = 0x14000000,
        defaultEv = 0xFF6E7683.toInt(),
        timeText = 0xFF6E7683.toInt(),
        todayPill = 0xFF1E7A5A.toInt(),   // deep green accent
        todayPillText = 0xFFF4F5F7.toInt(),
        todayTint = 0x0F1E7A5A,
        weekendTint = 0x00000000,
        pastText = 0xFFC3C7CE.toInt(),
        strike = 0x2E4A4F58,
        dark = false
    )

    val DARK = Palette(
        ink = 0xFFF4F5F7.toInt(),
        stone = 0xFFAEB4BF.toInt(),
        faint = 0x66F4F5F7,          // rgba(244,245,247,.4)
        line = 0x14FFFFFF,           // rgba(255,255,255,.08)
        defaultEv = 0xFF8B94A6.toInt(),
        timeText = 0xFF8B94A6.toInt(),
        todayPill = 0xFF8FE3C0.toInt(),   // mint accent
        todayPillText = 0xFF101318.toInt(),
        todayTint = 0x128FE3C0.toInt(),
        weekendTint = 0x00000000,
        pastText = 0xFF565B66.toInt(),
        strike = 0x30AEB4BF,
        dark = true
    )

    // Glass card (app screens). Widget uses the bg_* drawables instead.
    private const val CARD_FILL_DARK = 0x141820          // rgb
    private const val CARD_STROKE_DARK = 0x21FFFFFF      // rgba(255,255,255,.13)
    private const val CARD_FILL_LIGHT = 0xFFFFFF
    private const val CARD_STROKE_LIGHT = 0xE6FFFFFF.toInt()  // rgba(255,255,255,.9)

    private const val MAX_BYTES = 3_500_000
    private const val MAX_TITLE_LINES = 5

    private data class Seg(
        val ev: Ev, val cs: Int, val ce: Int, val rs: Boolean, val re: Boolean,
        var label: CharSequence = ""
    )

    private data class TimedRow(val ev: Ev, val lines: List<CharSequence>, val timeLabel: String)

    /**
     * Renders one week strip per the Float 2c spec.
     *
     * @param minHeightPx stretch the strip to at least this height.
     * @param isCurrentWeek current week gets the most opaque card (app) / full-strength dates (widget).
     * @param appCard true = draw this week as its own glass card (app screens);
     *   false = transparent strip for the widget (the widget's single card is the RemoteViews bg).
     * @param drawTopRule widget mode: hairline separator above this week (all but the first).
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
        isCurrentWeek: Boolean,
        appCard: Boolean,
        drawTopRule: Boolean
    ): Bitmap {
        val den = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * den
        fun ts(v: Float) = v * den * textScale
        val zone = ZoneId.systemDefault()
        val w = widthPx.coerceIn(240, 1600)

        // ---- geometry --------------------------------------------------------
        val sideInset = if (appCard) dp(12f) else dp(2f)
        val vGap = if (appCard) dp(5f) else 0f            // 10dp between stacked cards
        val cardPad = if (appCard) dp(10f) else dp(4f)
        val cardRadius = if (pal.dark) dp(22f) else dp(18f)
        val innerLeft = sideInset + cardPad
        val innerW = w - 2 * (sideInset + cardPad)
        val colGap = dp(3f)
        val colW = (innerW - 6 * colGap) / 7f
        fun colX(i: Int) = innerLeft + i * (colW + colGap)

        val dates = (0..6).map { weekStart.plusDays(it.toLong()) }
        val weekEnd = dates[6]

        // ---- bucket events ---------------------------------------------------
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

        // ---- greedy lane packing for all-day banners --------------------------
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

        // ---- paints ------------------------------------------------------------
        val dimWeek = !appCard && !isCurrentWeek   // widget: later weeks slightly dimmer
        val barText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(9.5f)
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
            textSize = if (dimWeek) ts(10.5f) else ts(11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        // ---- measure banners (single line, ellipsized) -------------------------
        val laneH = ts(20f)
        val laneGap = dp(3f)
        for (lane in lanes) for (s in lane) {
            val x0 = colX(s.cs)
            val x1 = colX(s.ce) + colW
            val avail = x1 - x0 - dp(14f)
            s.label = if (avail > 0)
                TextUtils.ellipsize(s.ev.title, barText, avail, TextUtils.TruncateAt.END) else ""
        }
        val laneBlock = if (lanes.isEmpty()) 0f else lanes.size * (laneH + laneGap)

        // ---- measure chips: time line + fully wrapped title ---------------------
        val chipPadH = dp(5f)
        val chipPadV = dp(3.5f)
        val chipGap = dp(3.5f)
        val timeLineH = ts(11f)
        val lineH = ts(13f)
        val titleW = (colW - 2 * chipPadH).coerceAtLeast(1f)
        val timedRows = Array(7) { ArrayList<TimedRow>() }
        for (i in 0..6) {
            for (ev in timed[i]) {
                val lt = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalTime()
                timedRows[i].add(TimedRow(ev, wrapN(ev.title, tPaint, titleW), fmtTime(lt.hour, lt.minute)))
            }
        }
        fun chipH(r: TimedRow) = 2 * chipPadV + timeLineH + r.lines.size * lineH
        val timedColH = (0..6).map { d -> timedRows[d].sumOf { (chipH(it) + chipGap).toDouble() }.toFloat() }
        val timedBlock = timedColH.maxOrNull() ?: 0f

        // ---- natural height, then stretch --------------------------------------
        val numH = ts(24f)
        val gapAfterNums = if (laneBlock > 0f || timedBlock > 0f) dp(3f) else 0f
        val gapAfterLanes = if (laneBlock > 0f && timedBlock > 0f) dp(2f) else 0f
        var height = (2 * vGap + 2 * cardPad + numH + gapAfterNums + laneBlock + gapAfterLanes + timedBlock).toInt()
        val minH = maxOf(dp(58f).toInt(), minHeightPx)
        if (height < minH) height = minH

        var bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)

        // ---- card / separators ---------------------------------------------------
        if (appCard) {
            val fillAlpha = if (pal.dark) (if (isCurrentWeek) 0.72f else 0.62f)
                else (if (isCurrentWeek) 0.68f else 0.58f)
            val fillRgb = if (pal.dark) CARD_FILL_DARK else CARD_FILL_LIGHT
            val a = (fillAlpha * 255).toInt()
            val card = RectF(sideInset, vGap, w - sideInset, height - vGap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (a shl 24) or fillRgb }
            cv.drawRoundRect(card, cardRadius, cardRadius, fill)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (den < 2f) 1f else den * 0.75f
                color = if (pal.dark) CARD_STROKE_DARK else CARD_STROKE_LIGHT
            }
            cv.drawRoundRect(card, cardRadius, cardRadius, stroke)
        } else if (drawTopRule) {
            val p = Paint().apply { color = pal.line }
            cv.drawRect(innerLeft, 0f, w - innerLeft,
                if (den * 0.5f < 1f) 1f else den * 0.5f, p)
        }

        val contentTop = vGap + cardPad

        // ---- date row: centered numbers, accent today circle, AUG 1, strike -----
        for (i in 0..6) {
            val date = dates[i]
            val isToday = date == today
            val isPast = date.isBefore(today)
            val cxMid = colX(i) + colW / 2f
            val numStr = if (date.dayOfMonth == 1 && !isToday)
                date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    .uppercase(Locale.getDefault()) + " 1"
            else date.dayOfMonth.toString()
            if (isToday) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.todayPill }
                cv.drawCircle(cxMid, contentTop + ts(10f), ts(10.5f), bgPaint)
                numPaint.color = pal.todayPillText
            } else if (date.dayOfMonth == 1) {
                numPaint.color = if (isPast) pal.pastText else pal.todayPill
            } else {
                numPaint.color = when {
                    isPast -> pal.pastText
                    dimWeek -> pal.stone
                    else -> pal.ink
                }
            }
            val fm = numPaint.fontMetrics
            val ty = contentTop + (ts(20f) - (fm.descent - fm.ascent)) / 2 - fm.ascent
            val tw = numPaint.measureText(numStr)
            cv.save()
            cv.clipRect(colX(i) - colGap / 2, contentTop, colX(i) + colW + colGap / 2, contentTop + numH)
            cv.drawText(numStr, cxMid - tw / 2f, ty, numPaint)
            if (strikePast && isPast) {
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = pal.pastText
                    strokeWidth = dp(1.2f)
                    strokeCap = Paint.Cap.ROUND
                }
                val midY = contentTop + ts(10f)
                cv.drawLine(cxMid - tw / 2f - dp(2f), midY, cxMid + tw / 2f + dp(2f), midY, sp)
            }
            cv.restore()
        }

        // ---- all-day banners: solid color, tone-matched dark text ----------------
        var y = contentTop + numH + gapAfterNums
        for (lane in lanes) {
            for (s in lane) {
                val c = if (s.ev.color == 0) pal.defaultEv else s.ev.color
                val x0 = colX(s.cs)
                val x1 = colX(s.ce) + colW
                val rectF = RectF(x0, y, x1, y + laneH)
                val isPastBanner = dates[s.ce].isBefore(today)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = c
                    alpha = if (isPastBanner) 0x66 else 0xFF
                }
                val rl = if (s.rs) dp(6f) else 0f
                val rr = if (s.re) dp(6f) else 0f
                drawRoundRectSides(cv, rectF, rl, rr, bg)
                if (s.label.isNotEmpty()) {
                    barText.color = darken(c, 0.72f)
                    if (isPastBanner) barText.alpha = 0x99
                    val fm = barText.fontMetrics
                    val base = y + (laneH - (fm.descent - fm.ascent)) / 2 - fm.ascent
                    cv.save()
                    cv.clipRect(rectF)
                    cv.drawText(s.label, 0, s.label.length, x0 + dp(7f), base, barText)
                    cv.restore()
                    barText.alpha = 0xFF
                }
            }
            y += laneH + laneGap
        }
        y += gapAfterLanes

        // ---- timed events: tinted chips -------------------------------------------
        if (timedBlock > 0f) {
            val chipAlpha = if (pal.dark) 0x30 else 0x26
            for (i in 0..6) {
                var ty = y
                val isPastDay = dates[i].isBefore(today)
                cv.save()
                cv.clipRect(colX(i) - colGap / 2, y, colX(i) + colW + colGap / 2, height.toFloat())
                for (row in timedRows[i]) {
                    val c = if (row.ev.color == 0) pal.defaultEv else row.ev.color
                    val h = chipH(row)
                    val x0 = colX(i)
                    val x1 = colX(i) + colW
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(c, chipAlpha) }
                    cv.drawRoundRect(RectF(x0, ty, x1, ty + h), dp(6f), dp(6f), bg)
                    // time on its own line, bold, in a lightened/darkened calendar color
                    mPaint.color = if (isPastDay) pal.pastText
                        else if (pal.dark) lighten(c, 0.55f) else darken(c, 0.45f)
                    val fmT = mPaint.fontMetrics
                    val tBase = ty + chipPadV + (timeLineH - (fmT.descent - fmT.ascent)) / 2 - fmT.ascent
                    cv.drawText(row.timeLabel, x0 + chipPadH, tBase, mPaint)
                    // full title below in ink, never truncated
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

    /** Wrap text to as many lines as needed (capped), last line ellipsized if over the cap. */
    private fun wrapN(text: String, paint: TextPaint, w: Float): List<CharSequence> {
        val out = ArrayList<CharSequence>(2)
        var rest = text.trim()
        val width = w.coerceAtLeast(1f)
        while (rest.isNotEmpty() && out.size < MAX_TITLE_LINES) {
            if (paint.measureText(rest) <= width) { out.add(rest); return out }
            if (out.size == MAX_TITLE_LINES - 1) {
                out.add(TextUtils.ellipsize(rest, paint, width, TextUtils.TruncateAt.END))
                return out
            }
            var n = paint.breakText(rest, true, width, null)
            if (n <= 0) n = 1
            val cut = rest.lastIndexOf(' ', n - 1)
            if (cut > 0 && cut >= n / 2) n = cut
            out.add(rest.substring(0, n).trimEnd())
            rest = rest.substring(n).trimStart()
        }
        return out
    }

    /** "9a" on the hour, "9:30" otherwise (compact, per the 2c mock). */
    private fun fmtTime(h: Int, m: Int): String {
        val ap = if (h < 12) "a" else "p"
        var hh = h % 12
        if (hh == 0) hh = 12
        return if (m == 0) "$hh$ap" else "$hh:${m.toString().padStart(2, '0')}"
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
