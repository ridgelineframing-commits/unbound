package construction.ridgeline.unbound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * Agenda mode: one bitmap per day (today + next N days), full-width rows —
 * "THU 16 · JUL" header, then each event on its own line.
 */
object AgendaRenderer {

    fun renderDay(
        ctx: Context,
        widthPx: Int,
        minHeightPx: Int,
        date: LocalDate,
        today: LocalDate,
        all: List<Ev>,
        pal: WeekRenderer.Palette,
        textScale: Float,
        appCard: Boolean = false,
        alphaScale: Float = 1f
    ): Bitmap {
        val den = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * den
        fun ts(v: Float) = v * den * textScale
        val zone = ZoneId.systemDefault()
        val w = widthPx.coerceIn(240, 1600)
        val sideInset = if (appCard) dp(12f) else 0f
        val vGap = if (appCard) dp(3f) else 0f

        // ---- collect this day's events -------------------------------------
        data class Row(val ev: Ev, val timeLabel: String?)
        val rows = ArrayList<Row>()
        for (ev in all) {
            if (ev.allDay) {
                val s = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val eLast = Instant.ofEpochMilli(ev.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                if (!date.isBefore(s) && !date.isAfter(eLast)) rows.add(Row(ev, null))
            } else {
                val d = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalDate()
                if (d == date) {
                    val lt = Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalTime()
                    rows.add(Row(ev, fmtTime(lt.hour, lt.minute)))
                }
            }
        }

        // ---- paints ----------------------------------------------------------
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(9.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            color = pal.faint
        }
        val tPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ts(10.5f)
            typeface = Typeface.DEFAULT_BOLD
            color = pal.timeText
        }

        // ---- measure ----------------------------------------------------------
        val padTop = vGap + dp(7f)
        val padBottom = vGap + dp(7f)
        val headH = ts(22f)
        val lineH = ts(17f)
        val leftPad = sideInset + dp(14f)
        val isPast = date.isBefore(today)
        val contentH = if (rows.isEmpty()) lineH else rows.size * lineH
        var height = (padTop + headH + contentH + padBottom).toInt()
        if (height < minHeightPx) height = minHeightPx
        if (height < dp(40f).toInt()) height = dp(40f).toInt()

        var bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)

        if (appCard) {
            // glass day card (Float spec): translucent fill + hairline border
            val fillAlpha = if (date == today) 0.78f else if (pal.dark) 0.62f else 0.58f
            val fillRgb = if (pal.dark) 0x141820 else 0xFFFFFF
            val card = RectF(sideInset, vGap, w - sideInset, height - vGap)
            val radius = dp(14f)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ((fillAlpha * alphaScale * 255).toInt().coerceIn(0, 255) shl 24) or fillRgb
            }
            cv.drawRoundRect(card, radius, radius, fill)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (den < 2f) 1f else den * 0.75f
                color = if (pal.dark) 0x21FFFFFF else 0xE6FFFFFF.toInt()
            }
            cv.drawRoundRect(card, radius, radius, stroke)
            if (date == today) {
                cv.save(); cv.clipRect(card); cv.drawColor(pal.todayTint); cv.restore()
            }
        } else {
            // widget: flat rows over the widget card
            if (date == today) {
                cv.drawColor(pal.todayTint)
            }
            val line = Paint().apply { color = pal.line }
            cv.drawRect(0f, 0f, w.toFloat(), if (den * 0.5f < 1f) 1f else den * 0.5f, line)
        }

        // ---- header: pill date + weekday/month --------------------------------
        val fmHead = headPaint.fontMetrics
        val headBase = padTop + (headH - (fmHead.descent - fmHead.ascent)) / 2 - fmHead.ascent
        val numStr = date.dayOfMonth.toString().padStart(2, ' ')
        if (date == today) {
            val pw = headPaint.measureText(numStr) + dp(12f)
            val pill = RectF(leftPad - dp(6f), padTop, leftPad - dp(6f) + pw, padTop + headH)
            cv.drawRoundRect(pill, ts(11f), ts(11f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.todayPill })
            headPaint.color = pal.todayPillText
        } else {
            headPaint.color = if (isPast) pal.pastText else pal.ink
        }
        cv.drawText(numStr, leftPad, headBase, headPaint)

        subPaint.color = if (isPast) pal.pastText else pal.faint
        val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault())
        val monName = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault())
        cv.drawText("$dayName · $monName", leftPad + headPaint.measureText(numStr) + dp(12f), headBase - ts(1f), subPaint)

        // strike for finished days (through the date, not a full-row slash)
        if (isPast) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.pastText
                strokeWidth = dp(1.4f)
                strokeCap = Paint.Cap.ROUND
            }
            val midY = padTop + headH / 2f
            cv.drawLine(leftPad - dp(3f), midY, leftPad + headPaint.measureText(numStr) + dp(3f), midY, sp)
        }

        // ---- events -------------------------------------------------------------
        var y = padTop + headH
        val textX = leftPad + dp(14f)
        val fm = tPaint.fontMetrics
        if (rows.isEmpty()) {
            tPaint.color = pal.faint
            cv.drawText("—", textX, y + lineH / 2f - (fm.ascent + fm.descent) / 2, tPaint)
        } else {
            cv.save()
            cv.clipRect(0f, 0f, w.toFloat(), height.toFloat())
            for (r in rows) {
                val c = if (r.ev.color == 0) pal.defaultEv else r.ev.color
                val midY = y + lineH / 2f
                val baseline = midY - (fm.ascent + fm.descent) / 2
                if (r.timeLabel == null) {
                    // all-day: small square chip
                    val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
                    cv.drawRoundRect(RectF(leftPad, midY - ts(4f), leftPad + ts(8f), midY + ts(4f)), ts(2f), ts(2f), chip)
                    tPaint.color = if (isPast) pal.faint else pal.ink
                    tPaint.typeface = Typeface.DEFAULT_BOLD
                    val avail = w - sideInset - textX - dp(8f)
                    val label = TextUtils.ellipsize(r.ev.title, tPaint, avail.coerceAtLeast(1f), TextUtils.TruncateAt.END)
                    cv.drawText(label, 0, label.length, textX, baseline, tPaint)
                    tPaint.typeface = Typeface.DEFAULT
                } else {
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
                    cv.drawCircle(leftPad + ts(4f), midY, ts(3f), dot)
                    val timeLabel = r.timeLabel + "  "
                    mPaint.color = if (isPast) pal.pastText
                        else if (pal.dark) WeekRenderer.lighten(c, 0.55f)
                        else WeekRenderer.darken(c, 0.45f)
                    cv.drawText(timeLabel, textX, baseline, mPaint)
                    val tw = mPaint.measureText(timeLabel)
                    tPaint.color = if (isPast) pal.faint else pal.ink
                    val avail = w - sideInset - textX - tw - dp(8f)
                    if (avail > 0) {
                        val label = TextUtils.ellipsize(r.ev.title, tPaint, avail, TextUtils.TruncateAt.END)
                        cv.drawText(label, 0, label.length, textX + tw, baseline, tPaint)
                    }
                }
                y += lineH
            }
            cv.restore()
        }

        // ---- keep bitmap under RemoteViews-friendly size ----------------------------
        // A launcher silently drops an oversized RemoteViews bitmap (Binder limit),
        // which reads as a blank widget. Downscale as WeekRenderer does.
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

    private const val MAX_BYTES = 3_500_000

    /** "9a" on the hour, "9:30" otherwise (compact, per the 2c mock). */
    private fun fmtTime(h: Int, m: Int): String {
        val ap = if (h < 12) "a" else "p"
        var hh = h % 12
        if (hh == 0) hh = 12
        return if (m == 0) "$hh$ap" else "$hh:${m.toString().padStart(2, '0')}"
    }
}
