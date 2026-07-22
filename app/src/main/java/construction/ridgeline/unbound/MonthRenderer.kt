package construction.ridgeline.unbound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * "Month" mode header: a mini month-grid for the current calendar month, drawn
 * as a glass card to match the agenda cards that scroll below it.
 *
 *  - each in-month day shows its number,
 *  - days that have events get up to 3 color dots (tinted by calendar color),
 *  - days already past are crossed out with an X,
 *  - today gets the accent circle.
 */
object MonthRenderer {

    private const val MAX_BYTES = 2_000_000

    fun render(
        ctx: Context,
        widthPx: Int,
        today: LocalDate,
        all: List<Ev>,
        pal: WeekRenderer.Palette,
        textScale: Float,
        weekStartsMonday: Boolean,
        alphaScale: Float = 1f
    ): Bitmap {
        val den = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * den
        fun ts(v: Float) = v * den * textScale
        val zone = ZoneId.systemDefault()
        val w = widthPx.coerceIn(240, 1600)

        val firstDow = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        val lastDow = firstDow.plus(6L)
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val gridStart = monthStart.with(TemporalAdjusters.previousOrSame(firstDow))
        val gridEnd = monthEnd.with(TemporalAdjusters.nextOrSame(lastDow))
        val rows = ((ChronoUnit.DAYS.between(gridStart, gridEnd) + 1) / 7).toInt()

        // ---- up to 3 event colors per date in the grid -----------------------
        val colorsByDate = HashMap<LocalDate, ArrayList<Int>>()
        fun addColor(d: LocalDate, c: Int) {
            if (d.isBefore(gridStart) || d.isAfter(gridEnd)) return
            val list = colorsByDate.getOrPut(d) { ArrayList() }
            if (list.size < 3 && !list.contains(c)) list.add(c)
        }
        for (ev in all) {
            val c = if (ev.color == 0) pal.defaultEv else ev.color
            if (ev.allDay) {
                val s = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val eLast = Instant.ofEpochMilli(ev.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                var d = if (s.isBefore(gridStart)) gridStart else s
                val last = if (eLast.isAfter(gridEnd)) gridEnd else eLast
                while (!d.isAfter(last)) { addColor(d, c); d = d.plusDays(1) }
            } else {
                addColor(Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalDate(), c)
            }
        }

        // ---- geometry --------------------------------------------------------
        val sideInset = dp(2f)
        val vGap = dp(5f)
        val cardPad = dp(10f)
        val innerLeft = sideInset + cardPad
        val innerW = w - 2 * (sideInset + cardPad)
        val colW = innerW / 7f
        val headerH = ts(16f)
        val cellH = colW.coerceAtMost(dp(58f)).coerceAtLeast(dp(34f))
        val topPad = vGap + cardPad
        val height = (topPad + headerH + rows * cellH + cardPad + vGap).toInt()

        var bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)

        // glass card
        val card = RectF(sideInset, vGap, w - sideInset, height - vGap)
        val radius = dp(16f)
        cv.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ((0.72f * alphaScale * 255).toInt().coerceIn(0, 255) shl 24) or
                (if (pal.dark) 0x141820 else 0xFFFFFF)
        })
        cv.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (den < 2f) 1f else den * 0.75f
            color = if (pal.dark) 0x21FFFFFF else 0xE6FFFFFF.toInt()
        })

        // weekday header (single-letter, per the week-start preference)
        val wkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(9f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            color = pal.faint
            textAlign = Paint.Align.CENTER
        }
        for (c in 0..6) {
            val name = firstDow.plus(c.toLong())
                .getDisplayName(TextStyle.NARROW, Locale.getDefault())
                .uppercase(Locale.getDefault())
            cv.drawText(name, innerLeft + c * colW + colW / 2f, topPad + headerH * 0.7f, wkPaint)
        }

        // cells
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(1.1f)
            strokeCap = Paint.Cap.ROUND
            color = pal.strike
        }
        val gridTop = topPad + headerH
        for (r in 0 until rows) {
            for (c in 0..6) {
                val date = gridStart.plusDays((r * 7 + c).toLong())
                val inMonth = date.monthValue == today.monthValue && date.year == today.year
                val isToday = date == today
                val isPast = date.isBefore(today)
                val cx = innerLeft + c * colW + colW / 2f
                val cellTop = gridTop + r * cellH
                val numCy = cellTop + cellH * 0.42f

                if (isToday) {
                    val rad = minOf(colW, cellH) * 0.30f
                    cv.drawCircle(cx, numCy, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.todayPill })
                }

                numPaint.color = when {
                    isToday -> pal.todayPillText
                    !inMonth -> pal.faint
                    isPast -> pal.pastText
                    else -> pal.ink
                }
                val fm = numPaint.fontMetrics
                cv.drawText(date.dayOfMonth.toString(), cx, numCy - (fm.ascent + fm.descent) / 2, numPaint)

                if (isPast && inMonth) {
                    val hx = colW * 0.24f
                    val hy = cellH * 0.24f
                    cv.drawLine(cx - hx, numCy - hy, cx + hx, numCy + hy, xPaint)
                    cv.drawLine(cx - hx, numCy + hy, cx + hx, numCy - hy, xPaint)
                }

                val colors = colorsByDate[date]
                if (inMonth && !colors.isNullOrEmpty()) {
                    val dotR = dp(2.1f)
                    val gap = dp(3.2f)
                    val n = colors.size
                    val totalW = n * (dotR * 2) + (n - 1) * gap
                    var dx = cx - totalW / 2f + dotR
                    val dy = cellTop + cellH * 0.80f
                    for (col in colors) {
                        val tinted = if (isPast) (0x80 shl 24) or (col and 0x00FFFFFF) else col
                        cv.drawCircle(dx, dy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tinted })
                        dx += dotR * 2 + gap
                    }
                }
            }
        }

        // keep under RemoteViews-friendly size (local bitmap, safe to recycle)
        val bytes = w * height * 4
        if (bytes > MAX_BYTES) {
            val s = Math.sqrt(MAX_BYTES.toDouble() / bytes).toFloat()
            val scaled = Bitmap.createScaledBitmap(
                bmp, (w * s).toInt().coerceAtLeast(1), (height * s).toInt().coerceAtLeast(1), true
            )
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }
}
