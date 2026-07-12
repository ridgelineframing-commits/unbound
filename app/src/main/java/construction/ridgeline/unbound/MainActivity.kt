package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.CalendarContract
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/**
 * Full-screen calendar: the same rolling weeks as the widget, at app size.
 * Scroll through ~a year, tap a day to open it in your calendar app.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ = 42
        private const val WEEKS_BACK = 4
        private const val WEEKS_FWD = 52
        private const val WEEK_COUNT = WEEKS_BACK + WEEKS_FWD
    }

    private lateinit var listView: ListView
    private lateinit var panel: ScrollView
    private lateinit var adapter: WeeksAdapter
    private var rangeStart: LocalDate = LocalDate.now()
    private var events: List<Ev> = emptyList()
    private var pal: WeekRenderer.Palette = WeekRenderer.LIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.app_weeks)
        panel = findViewById(R.id.panel)
        adapter = WeeksAdapter()
        listView.adapter = adapter

        findViewById<Button>(R.id.btn_grant).setOnClickListener {
            if (granted()) refreshAll()
            else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ)
        }
        findViewById<Button>(R.id.btn_today).setOnClickListener {
            listView.setSelection(WEEKS_BACK)
        }
        findViewById<ImageButton>(R.id.btn_app_settings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btn_close_panel).setOnClickListener {
            panel.visibility = View.GONE
            refreshAll()
        }

        // month label follows scrolling
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                if (total == 0) return
                val ws = rangeStart.plusWeeks(first.toLong())
                val mid = ws.plusDays(3)
                findViewById<TextView>(R.id.month_label).text =
                    mid.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                        .uppercase(Locale.getDefault()) + " " + mid.year
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.clearCache()
    }

    private fun granted(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------------
    // data + theme
    // ---------------------------------------------------------------------

    private fun refreshAll() {
        pal = if (Prefs.resolveDark(this)) WeekRenderer.DARK else WeekRenderer.LIGHT
        applyChrome()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = if (Prefs.weekStartsMonday(this)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        rangeStart = today.with(TemporalAdjusters.previousOrSame(firstDay))
            .minusWeeks(WEEKS_BACK.toLong())

        refreshPanel()

        if (!granted()) {
            panel.visibility = View.VISIBLE
            events = emptyList()
            adapter.clearCache()
            adapter.notifyDataSetChanged()
            return
        }

        val startMs = rangeStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = rangeStart.plusWeeks(WEEK_COUNT.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val cals = Prefs.cals(this)
        Thread {
            val evs = CalendarRepository.events(this, startMs, endMs, cals)
            runOnUiThread {
                events = evs
                adapter.clearCache()
                adapter.notifyDataSetChanged()
                if (listView.firstVisiblePosition == 0) listView.setSelection(WEEKS_BACK)
            }
        }.start()
        pokeWidgets()
    }

    private fun applyChrome() {
        val paper = if (pal.dark) 0xFF191917.toInt() else 0xFFF4F4F1.toInt()
        findViewById<LinearLayout>(R.id.root).setBackgroundColor(paper)
        findViewById<TextView>(R.id.month_label).setTextColor(pal.ink)
        findViewById<TextView>(R.id.brand).setTextColor(pal.faint)
        findViewById<ImageButton>(R.id.btn_app_settings).setColorFilter(pal.stone)
        val dowIds = intArrayOf(
            R.id.dow0, R.id.dow1, R.id.dow2, R.id.dow3, R.id.dow4, R.id.dow5, R.id.dow6
        )
        val labels = if (Prefs.weekStartsMonday(this))
            arrayOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        else
            arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        val todayLbl = LocalDate.now().dayOfWeek
            .getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US)
        for (i in 0..6) {
            val tv = findViewById<TextView>(dowIds[i])
            tv.text = labels[i]
            tv.setTextColor(if (labels[i] == todayLbl) pal.ink else pal.faint)
        }
    }

    private fun refreshPanel() {
        val ok = granted()
        findViewById<TextView>(R.id.status).text = if (ok)
            "Pick which calendars appear here and on the widget. Everything stays on your phone."
        else
            "Unbound reads your device's calendar to draw this view and the widget. Nothing leaves your phone — no account, no sign-in."
        findViewById<Button>(R.id.btn_grant).apply {
            text = if (ok) "Calendar access granted" else "Grant calendar access"
            visibility = if (ok) View.GONE else View.VISIBLE
        }

        val box = findViewById<LinearLayout>(R.id.cal_list)
        box.removeAllViews()
        if (ok) {
            val sel = Prefs.cals(this)
            for (c in CalendarRepository.calendars(this)) {
                val cb = CheckBox(this)
                cb.text = c.name
                cb.setTextColor(pal.ink)
                cb.isChecked = sel == null || sel.contains(c.id.toString())
                cb.tag = c.id.toString()
                cb.setOnCheckedChangeListener { _, _ -> saveCals(box) }
                box.addView(cb)
            }
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

    private fun openDay(date: LocalDate) {
        try {
            val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, millis)
            startActivity(Intent(Intent.ACTION_VIEW).setData(builder.build()))
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
    }

    // ---------------------------------------------------------------------
    // weeks list
    // ---------------------------------------------------------------------

    private inner class WeeksAdapter : BaseAdapter() {

        // small LRU of rendered weeks; evicted bitmaps are left to the GC
        private val cache = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) =
                size > 12
        }

        fun clearCache() = cache.clear()

        override fun getCount(): Int = WEEK_COUNT
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val iv = (convertView as? ImageView) ?: ImageView(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val width = if (parent.width > 0) parent.width
            else resources.displayMetrics.widthPixels
            val listH = if (parent.height > 0) parent.height
            else (resources.displayMetrics.heightPixels * 0.8f).toInt()
            val minWeekH = maxOf(listH / 4, (110 * resources.displayMetrics.density).toInt())

            val today = LocalDate.now()
            val ws = rangeStart.plusWeeks(position.toLong())
            val bmp = cache[position] ?: WeekRenderer.renderWeek(
                this@MainActivity, width, minWeekH, ws, today, events,
                pal, Prefs.textScale(this@MainActivity), Prefs.strikePast(this@MainActivity),
                isFirstWeek = position == 0
            ).also { cache[position] = it }
            iv.setImageBitmap(bmp)

            var downX = 0f
            var downY = 0f
            val slop = 24 * resources.displayMetrics.density
            iv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                            val col = (e.x / (v.width / 7f)).toInt().coerceIn(0, 6)
                            openDay(ws.plusDays(col.toLong()))
                            v.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            return iv
        }
    }
}
