package construction.ridgeline.unbound

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class UnboundWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "construction.ridgeline.unbound.REFRESH"

        private const val HEADER_DP = 40
        private const val STRIP_DP = 20

        fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val den = ctx.resources.displayMetrics.density
            val opts = mgr.getAppWidgetOptions(id)
            val portrait =
                ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

            // Portrait widgets are minWidth x maxHeight; landscape are maxWidth x minHeight.
            val wDp = opts.getInt(
                if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0
            ).let { if (it <= 0) 320 else it }
            val hDp = opts.getInt(
                if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0
            ).let { if (it <= 0) 180 else it }

            val showHeader = Prefs.showHeader(ctx)
            val reservedDp = (if (showHeader) HEADER_DP else 0) + STRIP_DP
            Prefs.setWidthPx(ctx, id, (wDp * den).toInt())
            Prefs.setListHeightPx(ctx, id, ((hDp - reservedDp).coerceAtLeast(0) * den).toInt())

            val dark = Prefs.resolveDark(ctx)
            val pal = if (dark) WeekRenderer.DARK else WeekRenderer.LIGHT
            val alpha = Prefs.opacity(ctx) * 255 / 100

            val rv = RemoteViews(ctx.packageName, R.layout.widget_unbound)

            // themed translucent card
            rv.setImageViewResource(R.id.bg, if (dark) R.drawable.bg_dark else R.drawable.bg_light)
            rv.setInt(R.id.bg, "setImageAlpha", alpha)

            // header
            rv.setViewVisibility(R.id.header, if (showHeader) View.VISIBLE else View.GONE)
            if (showHeader) {
                val monday = Prefs.weekStartsMonday(ctx)
                val start = LocalDate.now().with(
                    TemporalAdjusters.previousOrSame(if (monday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
                )
                val monthLabel = start.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    .uppercase(Locale.getDefault()) + " " + start.year
                rv.setTextViewText(R.id.month_label, monthLabel)
                rv.setTextColor(R.id.month_label, pal.ink)
                rv.setTextColor(R.id.brand, pal.faint)
                rv.setTextColor(R.id.weeks_label, pal.faint)
                rv.setTextViewText(R.id.weeks_label, Prefs.weeks(ctx, id).toString() + "w")
                rv.setInt(R.id.btn_refresh, "setColorFilter", pal.stone)
                rv.setInt(R.id.btn_settings, "setColorFilter", pal.stone)
            }

            // day-of-week strip (order follows week-start setting)
            val dowIds = intArrayOf(
                R.id.dow0, R.id.dow1, R.id.dow2, R.id.dow3, R.id.dow4, R.id.dow5, R.id.dow6
            )
            val labels = if (Prefs.weekStartsMonday(ctx))
                arrayOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
            else
                arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
            val todayDow = LocalDate.now().dayOfWeek
            for (i in 0..6) {
                rv.setTextViewText(dowIds[i], labels[i])
                val isToday = labels[i] == todayDow.getDisplayName(TextStyle.SHORT, Locale.US)
                    .uppercase(Locale.US)
                rv.setTextColor(dowIds[i], if (isToday) pal.ink else pal.faint)
            }

            rv.setTextColor(R.id.empty, pal.stone)

            // list adapter
            val svc = Intent(ctx, WeekWidgetService::class.java)
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            rv.setRemoteAdapter(R.id.week_list, svc)
            rv.setEmptyView(R.id.week_list, R.id.empty)

            // clicks
            val refresh = Intent(ctx, UnboundWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("unbound://refresh/$id")
            }
            rv.setOnClickPendingIntent(
                R.id.btn_refresh,
                PendingIntent.getBroadcast(ctx, id, refresh, flags())
            )

            val cfg = Intent(ctx, ConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("unbound://config/$id")
            }
            rv.setOnClickPendingIntent(
                R.id.btn_settings,
                PendingIntent.getActivity(ctx, id, cfg, flags())
            )

            rv.setOnClickPendingIntent(
                R.id.brand,
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags())
            )
            rv.setPendingIntentTemplate(
                R.id.week_list,
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
            )

            mgr.updateAppWidget(id, rv)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.week_list)
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, UnboundWidgetProvider::class.java))
            for (id in ids) updateWidget(ctx, mgr, id)
        }

        private fun flags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(ctx, mgr, id)
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        updateWidget(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                updateWidget(ctx, mgr, id)
            } else {
                updateAll(ctx)
            }
        }
    }
}
