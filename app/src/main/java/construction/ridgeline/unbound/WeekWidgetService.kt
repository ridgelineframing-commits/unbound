package construction.ridgeline.unbound

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.CalendarContract
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class WeekWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WeekFactory(applicationContext, intent)
}

private const val AGENDA_DAYS = 30 // today + next 29 (one-month span; the list scrolls)

class WeekFactory(private val ctx: Context, intent: Intent) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var items: List<Bitmap> = emptyList()
    private var itemDates: List<LocalDate> = emptyList() // first date of each item
    private var agenda = false

    override fun onCreate() {}

    override fun onDataSetChanged() {
        releaseItems()
        val den = ctx.resources.displayMetrics.density
        val widthPx = Prefs.widthPx(ctx, widgetId)
        val listHeightPx = Prefs.listHeightPx(ctx, widgetId)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        agenda = Prefs.mode(ctx, widgetId) == 1

        val granted = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

        val pal = if (Prefs.resolveDark(ctx)) WeekRenderer.DARK else WeekRenderer.LIGHT
        val textScale = Prefs.textScale(ctx)
        val alphaScale = (Prefs.opacity(ctx) / 100f).coerceIn(0.2f, 1f)

        if (agenda) {
            val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = today.plusDays(AGENDA_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val events = if (granted) CalendarRepository.events(ctx, startMs, endMs, Prefs.hiddenCals(ctx)) else emptyList()
            // Days stretch to fill the widget; floor keeps rows readable.
            val minDayH = maxOf(if (listHeightPx > 0) listHeightPx / AGENDA_DAYS else 0, (44 * den).toInt())
            val list = ArrayList<Bitmap>(AGENDA_DAYS)
            val dates = ArrayList<LocalDate>(AGENDA_DAYS)
            for (i in 0 until AGENDA_DAYS) {
                val d = today.plusDays(i.toLong())
                list.add(AgendaRenderer.renderDay(ctx, widthPx, minDayH, d, today, events, pal, textScale,
                    appCard = true, alphaScale = alphaScale))
                dates.add(d)
            }
            items = list
            itemDates = dates
        } else {
            val weeks = Prefs.weeks(ctx, widgetId)
            val firstDay = if (Prefs.weekStartsMonday(ctx)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
            val start = today.with(TemporalAdjusters.previousOrSame(firstDay))
            val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = start.plusWeeks(weeks.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val events = if (granted) CalendarRepository.events(ctx, startMs, endMs, Prefs.hiddenCals(ctx)) else emptyList()

            // Weeks stretch to fill the widget; floor keeps cells readable even when the
            // launcher under-reports the widget height.
            val minWeekH = maxOf(if (listHeightPx > 0) listHeightPx / weeks else 0, (84 * den).toInt())
            val strike = Prefs.strikePast(ctx)
            val list = ArrayList<Bitmap>(weeks)
            val dates = ArrayList<LocalDate>(weeks)
            for (i in 0 until weeks) {
                val ws = start.plusWeeks(i.toLong())
                list.add(
                    WeekRenderer.renderWeek(
                        ctx, widthPx, minWeekH, ws, today, events,
                        pal, textScale, strike,
                        isCurrentWeek = i == 0, appCard = true, drawTopRule = false,
                        alphaScale = alphaScale
                    )
                )
                dates.add(ws)
            }
            items = list
            itemDates = dates
        }
    }

    override fun onDestroy() {
        releaseItems()
    }

    /**
     * Drop references to the rendered bitmaps and let the GC reclaim them.
     * We must NOT recycle() them here: each was handed to the launcher via
     * RemoteViews.setImageViewBitmap and may still be marshalling on another
     * thread when the next refresh arrives — recycling mid-flight blanks the
     * widget ("trying to use a recycled bitmap"). The item count is tiny
     * (<=4 weeks / <=30 days), so leaving them to GC is cheap.
     */
    private fun releaseItems() {
        items = emptyList()
        itemDates = emptyList()
    }

    override fun getCount(): Int = items.size

    private fun dayIntent(date: LocalDate): Intent {
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Intent().putExtra(DayOpenActivity.EXTRA_DAY_MS, millis)
    }

    override fun getViewAt(position: Int): RemoteViews {
        return if (agenda) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_day_item)
            if (position < items.size) {
                rv.setImageViewBitmap(R.id.day_img, items[position])
                rv.setOnClickFillInIntent(R.id.day_img, dayIntent(itemDates[position]))
            }
            rv
        } else {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_week_item)
            if (position < items.size) {
                rv.setImageViewBitmap(R.id.week_img, items[position])
                val ws = itemDates[position]
                val zoneIds = intArrayOf(
                    R.id.tap0, R.id.tap1, R.id.tap2, R.id.tap3, R.id.tap4, R.id.tap5, R.id.tap6
                )
                for (i in 0..6) {
                    rv.setOnClickFillInIntent(zoneIds[i], dayIntent(ws.plusDays(i.toLong())))
                }
            }
            rv
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 2
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
