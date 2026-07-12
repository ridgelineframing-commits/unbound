package construction.ridgeline.unbound

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
            // Some launchers under-report or omit these, so be defensive: never trust a
            // value below the other bound, and fall back to a sane 4x3-ish size.
            val minWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val maxWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val minHdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxHdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val wDp = (if (portrait) minWdp else maxOf(maxWdp, minWdp))
                .let { if (it <= 0) 320 else it }
            val hDp = (if (portrait) maxOf(maxHdp, minHdp) else minHdp)
                .let { if (it <= 0) 340 else it }

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
                rv.setTextViewText(
                    R.id.weeks_label,
                    if (Prefs.mode(ctx, id) == 1) "7d" else Prefs.weeks(ctx, id).toString() + "w"
                )
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
            // Tap a day: the item's fill-in intent supplies the day (as an extra), and
            // this explicit template trampolines into the calendar app. Android 14
            // forbids mutable PendingIntents with implicit intents, so the template
            // must target our own activity.
            rv.setPendingIntentTemplate(
                R.id.week_list,
                PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, DayOpenActivity::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            mgr.updateAppWidget(id, rv)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.week_list)

            // Never let background scheduling break a widget update.
            try { scheduleMidnightRefresh(ctx) } catch (_: Exception) {}
            try { scheduleCalendarChangeJob(ctx) } catch (_: Exception) {}
        }

        /** Refresh just after midnight so "today" (pill, strike-through) moves on its own. */
        private fun scheduleMidnightRefresh(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val next = LocalDate.now().plusDays(1).atTime(LocalTime.of(0, 2))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                ctx, 9001,
                Intent(ctx, UnboundWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                    data = Uri.parse("unbound://midnight")
                },
                flags()
            )
            am.setInexactRepeating(AlarmManager.RTC, next, AlarmManager.INTERVAL_DAY, pi)
        }

        /** Refresh whenever anything in the device calendar changes. */
        fun scheduleCalendarChangeJob(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(9002, ComponentName(ctx, CalendarChangeJobService::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(2000)
                .setTriggerContentMaxDelay(30000)
                .build()
            js.schedule(job)
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
