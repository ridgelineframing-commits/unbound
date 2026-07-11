package construction.ridgeline.unbound

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button

class ConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_config)

        val cur = Prefs.weeks(this, widgetId)
        val ids = intArrayOf(R.id.wk1, R.id.wk2, R.id.wk3, R.id.wk4)
        ids.forEachIndexed { i, rid ->
            val b = findViewById<Button>(rid)
            if (i + 1 == cur) {
                b.setBackgroundColor(0xFF1B1B19.toInt())
                b.setTextColor(0xFFFFFFFF.toInt())
            }
            b.setOnClickListener { choose(i + 1) }
        }
    }

    private fun choose(weeks: Int) {
        Prefs.setWeeks(this, widgetId, weeks)
        val mgr = AppWidgetManager.getInstance(this)
        UnboundWidgetProvider.updateWidget(this, mgr, widgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
    }
}
