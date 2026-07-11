package construction.ridgeline.unbound

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

class UnboundWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "construction.ridgeline.unbound.REFRESH"

        fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val den = ctx.resources.displayMetrics.density
            val opts = mgr.getAppWidgetOptions(id)
            val maxWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
                .let { if (it <= 0) 320 else it }
            Prefs.setWidthPx(ctx, id, (maxWdp * den).toInt())

            val rv = RemoteViews(ctx.packageName, R.layout.widget_unbound)

            val svc = Intent(ctx, WeekWidgetService::class.java)
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            rv.setRemoteAdapter(R.id.week_list, svc)
            rv.setEmptyView(R.id.week_list, R.id.empty)

            rv.setTextViewText(R.id.weeks_label, Prefs.weeks(ctx, id).toString() + "w")

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
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, UnboundWidgetProvider::class.java))
                for (wid in ids) updateWidget(ctx, mgr, wid)
            }
        }
    }
}
