package construction.ridgeline.unbound

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

class WeekFactory(private val ctx: Context, intent: Intent) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var items: List<Bitmap> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        recycleAll()
        val weeks = Prefs.weeks(ctx, widgetId)
        val widthPx = Prefs.widthPx(ctx, widgetId)
        val listHeightPx = Prefs.listHeightPx(ctx, widgetId)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = if (Prefs.weekStartsMonday(ctx)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        val start = today.with(TemporalAdjusters.previousOrSame(firstDay))
        val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = start.plusWeeks(weeks.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

        val granted = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val events = if (granted) CalendarRepository.events(ctx, startMs, endMs, Prefs.cals(ctx)) else emptyList()

        val pal = if (Prefs.resolveDark(ctx)) WeekRenderer.DARK else WeekRenderer.LIGHT
        val textScale = Prefs.textScale(ctx)
        val strike = Prefs.strikePast(ctx)

        // If content is sparse, stretch each week so the calendar fills the widget.
        val minWeekH = if (listHeightPx > 0) listHeightPx / weeks else 0

        val list = ArrayList<Bitmap>(weeks)
        for (i in 0 until weeks) {
            val ws = start.plusWeeks(i.toLong())
            list.add(
                WeekRenderer.renderWeek(
                    ctx, widthPx, minWeekH, ws, today, events,
                    pal, textScale, strike, isFirstWeek = i == 0
                )
            )
        }
        items = list
    }

    override fun onDestroy() {
        recycleAll()
    }

    private fun recycleAll() {
        items.forEach { if (!it.isRecycled) it.recycle() }
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_week_item)
        if (position < items.size) rv.setImageViewBitmap(R.id.week_img, items[position])
        rv.setOnClickFillInIntent(R.id.week_img, Intent())
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
