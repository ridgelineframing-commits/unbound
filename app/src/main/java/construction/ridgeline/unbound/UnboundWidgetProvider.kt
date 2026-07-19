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
import android.graphics.Typeface
import android.os.Bundle
import android.provider.CalendarContract
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
            val reservedDp = if (showHeader) HEADER_DP else 0 // no weekday strip in Float
            Prefs.setWidthPx(ctx, id, (wDp * den).toInt())
            Prefs.setListHeightPx(ctx, id, ((hDp - reservedDp).coerceAtLeast(0) * den).toInt())

            val dark = Prefs.resolveDark(ctx)
            val pal = if (dark) WeekRenderer.DARK else WeekRenderer.LIGHT

            val rv = RemoteViews(ctx.packageName, R.layout.widget_unbound)

            // Float: weeks float as their own glass cards (drawn in the renderer),
            // so the widget's full-bleed card is gone. Opacity now scales the cards.
            rv.setViewVisibility(R.id.bg, View.GONE)

            // header (Float 3c: "July 2026" sentence case, year at 45%, glyphs at 50%)
            rv.setViewVisibility(R.id.header, if (showHeader) View.VISIBLE else View.GONE)
            if (showHeader) {
                // header floats in its own small glass pill, like a card
                rv.setInt(R.id.header, "setBackgroundResource",
                    if (dark) R.drawable.bg_dark else R.drawable.bg_light)
                val monday = Prefs.weekStartsMonday(ctx)
                val start = LocalDate.now().with(
                    TemporalAdjusters.previousOrSame(if (monday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
                )
                val monthName = start.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val label = SpannableString("$monthName ${start.year}")
                label.setSpan(StyleSpan(Typeface.BOLD), 0, monthName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(ForegroundColorSpan(pal.ink), 0, monthName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(ForegroundColorSpan(pal.faint), monthName.length + 1, label.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(RelativeSizeSpan(0.82f), monthName.length + 1, label.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rv.setTextViewText(R.id.month_label, label)
                rv.setTextColor(R.id.month_label, pal.ink)
                rv.setViewVisibility(R.id.brand, View.GONE)
                rv.setTextColor(R.id.weeks_label, pal.faint)
                rv.setTextViewText(
                    R.id.weeks_label,
                    if (Prefs.mode(ctx, id) == 1) "7d" else Prefs.weeks(ctx, id).toString() + "w"
                )
                val glyph = (0x80 shl 24) or (pal.ink and 0x00FFFFFF) // 50% alpha ink
                rv.setInt(R.id.btn_refresh, "setColorFilter", glyph)
                rv.setInt(R.id.btn_settings, "setColorFilter", glyph)
            }

            // Float 2c/3c has no weekday strip — dates carry the row
            rv.setViewVisibility(R.id.dow_strip, View.GONE)

            rv.setTextColor(R.id.empty, pal.stone)
            rv.setInt(R.id.empty, "setBackgroundResource",
                if (dark) R.drawable.bg_dark else R.drawable.bg_light)

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
        when (intent.action) {
            ACTION_REFRESH -> {
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
            // After a reboot or an app update the process is fresh: repopulate every
            // widget and re-arm the midnight alarm + calendar-change job (both lost on
            // reboot). updateAll -> updateWidget reschedules them per widget.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> updateAll(ctx)
        }
    }
}
