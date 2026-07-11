package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val REQ = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_grant).setOnClickListener {
            if (granted()) refreshState()
            else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun granted(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun refreshState() {
        val ok = granted()
        findViewById<TextView>(R.id.status).text = if (ok)
            "Calendar access is on. Add the Unbound widget: long-press your home screen \u2192 Widgets \u2192 Unbound, then pick 1\u20134 weeks."
        else
            "Unbound reads your device's calendar to draw the widget. Nothing leaves your phone \u2014 no account, no sign-in."
        findViewById<Button>(R.id.btn_grant).text = if (ok) "Calendar access granted" else "Grant calendar access"

        val box = findViewById<LinearLayout>(R.id.cal_list)
        box.removeAllViews()
        if (ok) {
            val sel = Prefs.cals(this)
            for (c in CalendarRepository.calendars(this)) {
                val cb = CheckBox(this)
                cb.text = c.name
                cb.isChecked = sel == null || sel.contains(c.id.toString())
                cb.tag = c.id.toString()
                cb.setOnCheckedChangeListener { _, _ -> saveCals(box) }
                box.addView(cb)
            }
            pokeWidgets()
        }
    }

    private fun saveCals(box: LinearLayout) {
        val set = HashSet<String>()
        for (i in 0 until box.childCount) {
            val cb = box.getChildAt(i) as CheckBox
            if (cb.isChecked) set.add(cb.tag as String)
        }
        Prefs.setCals(this, set)
        pokeWidgets()
    }

    private fun pokeWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, UnboundWidgetProvider::class.java))
        for (id in ids) UnboundWidgetProvider.updateWidget(this, mgr, id)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshState()
    }
}
