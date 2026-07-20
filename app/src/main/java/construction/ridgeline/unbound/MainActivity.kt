package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/**
 * Full-screen calendar per the Float 2c spec: glass week cards floating over
 * ambient radial glows, "July 2026" header with a Weeks | Agenda pill.
 * Scroll through ~a year, tap a day to open it in your calendar app.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ = 42
        private const val WEEKS_BACK = 4
        private const val WEEKS_FWD = 52
        private const val WEEK_COUNT = WEEKS_BACK + WEEKS_FWD
        private const val AGENDA_DAYS = 30
    }

    private lateinit var listView: ListView
    private lateinit var panel: ScrollView
    private lateinit var adapter: WeeksAdapter
    private var rangeStart: LocalDate = LocalDate.now()
    private var events: List<Ev> = emptyList()
    private var pal: WeekRenderer.Palette = WeekRenderer.LIGHT
    private var viewMode = 0 // 0 = weeks, 1 = agenda (app-local)

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
            listView.setSelection(if (viewMode == 0) WEEKS_BACK else 0)
        }
        findViewById<ImageButton>(R.id.btn_app_settings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btn_close_panel).setOnClickListener {
            panel.visibility = View.GONE
            refreshAll()
        }
        findViewById<TextView>(R.id.seg_weeks).setOnClickListener { setMode(0) }
        findViewById<TextView>(R.id.seg_agenda).setOnClickListener { setMode(1) }

        setupPanelControls()

        // month label follows scrolling (weeks mode)
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                if (total == 0) return
                val mid = if (viewMode == 0) rangeStart.plusWeeks(first.toLong()).plusDays(3)
                    else LocalDate.now().plusDays(first.toLong())
                setMonthLabel(mid)
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

    private fun setMode(m: Int) {
        if (viewMode == m) return
        viewMode = m
        styleSegments()
        adapter.clearCache()
        adapter.notifyDataSetChanged()
        listView.setSelection(if (m == 0) WEEKS_BACK else 0)
    }

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
        val hidden = Prefs.hiddenCals(this)
        Thread {
            val evs = CalendarRepository.events(this, startMs, endMs, hidden)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                events = evs
                adapter.clearCache()
                adapter.notifyDataSetChanged()
                if (listView.firstVisiblePosition == 0 && viewMode == 0) {
                    listView.setSelection(WEEKS_BACK)
                }
            }
        }.start()
        pokeWidgets()
    }

    /** Float bg: base ink + soft ambient radial glows (app screens only). */
    private fun glowBackground(): LayerDrawable {
        val base = GradientDrawable().apply {
            setColor(if (pal.dark) 0xFF101318.toInt() else 0xFFE9EBEF.toInt())
        }
        val dm = resources.displayMetrics
        val r = maxOf(dm.widthPixels, dm.heightPixels) * 0.7f
        fun glow(color: Int, cx: Float, cy: Float) = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(color, color and 0x00FFFFFF)
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = r
            setGradientCenter(cx, cy)
        }
        val glows = if (pal.dark) arrayOf(
            glow(0x8C405C96.toInt(), 0.15f, 0.05f),  // blue rgba(64,92,150,.55)
            glow(0x732E6E62, 0.95f, 0.45f),          // green rgba(46,110,98,.45)
            glow(0x59785AAA, 0.25f, 0.95f)           // purple rgba(120,90,170,.35)
        ) else arrayOf(
            glow(0x8C96AFE0.toInt(), 0.15f, 0.05f),  // blue rgba(150,175,224,.55)
            glow(0x8094CDBE.toInt(), 0.95f, 0.45f),  // teal rgba(148,205,190,.5)
            glow(0x73C4AAD8, 0.25f, 0.95f)           // lilac rgba(196,170,216,.45)
        )
        return LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(base, *glows))
    }

    private fun setMonthLabel(anchor: LocalDate) {
        val monthName = anchor.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val label = SpannableString("$monthName ${anchor.year}")
        label.setSpan(StyleSpan(Typeface.BOLD), 0, monthName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(ForegroundColorSpan(pal.ink), 0, monthName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(ForegroundColorSpan(pal.faint), monthName.length + 1, label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(RelativeSizeSpan(0.62f), monthName.length + 1, label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.month_label).text = label
    }

    private fun pill(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun styleSegments() {
        val box = findViewById<LinearLayout>(R.id.seg_view)
        box.background = pill(if (pal.dark) 0x12FFFFFF else 0x11000000, 12f)
        val weeks = findViewById<TextView>(R.id.seg_weeks)
        val agenda = findViewById<TextView>(R.id.seg_agenda)
        fun style(tv: TextView, on: Boolean) {
            if (on) {
                tv.background = pill(pal.todayPill, 10f)
                tv.setTextColor(pal.todayPillText)
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                tv.background = null
                tv.setTextColor(pal.faint)
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }
        style(weeks, viewMode == 0)
        style(agenda, viewMode == 1)
    }

    private fun applyChrome() {
        findViewById<LinearLayout>(R.id.root).background = glowBackground()
        setMonthLabel(LocalDate.now())
        styleSegments()
        findViewById<View>(R.id.dow_strip).visibility = View.GONE
        val todayBtn = findViewById<Button>(R.id.btn_today)
        todayBtn.setTextColor(pal.stone)
        todayBtn.background = pill(if (pal.dark) 0x12FFFFFF else 0x11000000, 12f)
        findViewById<ImageButton>(R.id.btn_app_settings)
            .setColorFilter((0x80 shl 24) or (pal.ink and 0x00FFFFFF))
        panel.setBackgroundColor(if (pal.dark) 0xF2161A22.toInt() else 0xF2F6F7FA.toInt())
        findViewById<TextView>(R.id.status).setTextColor(pal.stone)
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
        stylePanelControls()

        val box = findViewById<LinearLayout>(R.id.cal_list)
        box.removeAllViews()
        if (!ok) return

        // The calendar scan (instanceCounts over 30 days + calendars) can be heavy
        // on busy accounts — do it off the main thread, then build the rows on the UI.
        val nowMs = System.currentTimeMillis()
        Thread {
            val counts = CalendarRepository.instanceCounts(this, nowMs, nowMs + 30L * 24 * 60 * 60 * 1000)
            val cals = CalendarRepository.calendars(this)
            runOnUiThread {
                if (isFinishing || isDestroyed || !granted()) return@runOnUiThread
                val hidden = Prefs.hiddenCals(this)
                box.removeAllViews()
                for (c in cals) {
                    val cb = CheckBox(this)
                    val n = counts[c.id] ?: 0
                    val label = StringBuilder(c.name)
                    label.append("\n").append(n)
                        .append(if (n == 1) " event" else " events")
                        .append(" on device, next 30 days")
                    if (c.account.isNotEmpty() && !c.name.contains(c.account)) {
                        label.append(" · ").append(c.account)
                    }
                    if (!c.syncOn) {
                        label.append("\nSYNC OFF — Google Calendar app › this calendar › Sync")
                    }
                    cb.text = label.toString()
                    cb.setTextColor(if (c.syncOn) pal.ink else pal.faint)
                    cb.isChecked = !hidden.contains(c.id.toString())
                    cb.tag = c.id.toString()
                    cb.setOnCheckedChangeListener { _, _ -> saveCals(box) }
                    box.addView(cb)
                }
            }
        }.start()
    }

    // ---- in-app settings panel (mirrors the widget's gear) -------------------

    /** Attach listeners once and seed the stateful controls (switches / seekbar)
     *  BEFORE the listeners are attached, so seeding doesn't fire them. */
    private fun setupPanelControls() {
        listOf(R.id.app_th_auto, R.id.app_th_light, R.id.app_th_dark)
            .forEachIndexed { i, id ->
                findViewById<Button>(id).setOnClickListener {
                    Prefs.setTheme(this, i); onGlobalSettingChanged()
                }
            }
        listOf(R.id.app_tx_s, R.id.app_tx_m, R.id.app_tx_l)
            .forEachIndexed { i, id ->
                findViewById<Button>(id).setOnClickListener {
                    Prefs.setTextSize(this, i); onGlobalSettingChanged()
                }
            }
        listOf(R.id.app_md_weeks, R.id.app_md_agenda)
            .forEachIndexed { i, id ->
                findViewById<Button>(id).setOnClickListener {
                    setWidgetModeAll(i); stylePanelControls()
                }
            }
        listOf(R.id.app_wk1, R.id.app_wk2, R.id.app_wk3, R.id.app_wk4)
            .forEachIndexed { i, id ->
                findViewById<Button>(id).setOnClickListener {
                    setWidgetWeeksAll(i + 1); stylePanelControls()
                }
            }

        val seek = findViewById<SeekBar>(R.id.app_opacity_seek)
        seek.progress = Prefs.opacity(this) - 20
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.app_opacity_label).text =
                    "WIDGET OPACITY — ${progress + 20}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Prefs.setOpacity(this@MainActivity, (sb?.progress ?: 80) + 20)
                onGlobalSettingChanged()
            }
        })

        val header = findViewById<Switch>(R.id.app_sw_header)
        header.isChecked = Prefs.showHeader(this)
        header.setOnCheckedChangeListener { _, on -> Prefs.setShowHeader(this, on); onGlobalSettingChanged() }

        val monday = findViewById<Switch>(R.id.app_sw_monday)
        monday.isChecked = Prefs.weekStartsMonday(this)
        monday.setOnCheckedChangeListener { _, on -> Prefs.setWeekStartsMonday(this, on); onGlobalSettingChanged() }

        val strike = findViewById<Switch>(R.id.app_sw_strike)
        strike.isChecked = Prefs.strikePast(this)
        strike.setOnCheckedChangeListener { _, on -> Prefs.setStrikePast(this, on); onGlobalSettingChanged() }
    }

    /** Re-style the tap-to-select groups + labels for the current theme/values.
     *  Never touches the switches/seekbar (that would re-fire their listeners). */
    private fun stylePanelControls() {
        fun styleGroup(ids: List<Int>, selected: Int) {
            ids.forEachIndexed { i, id ->
                val b = findViewById<Button>(id)
                if (i == selected) {
                    b.setBackgroundColor(pal.todayPill); b.setTextColor(pal.todayPillText)
                } else {
                    b.setBackgroundColor(if (pal.dark) 0x1FFFFFFF else 0x14000000)
                    b.setTextColor(pal.stone)
                }
            }
        }
        styleGroup(listOf(R.id.app_th_auto, R.id.app_th_light, R.id.app_th_dark), Prefs.theme(this))
        styleGroup(listOf(R.id.app_tx_s, R.id.app_tx_m, R.id.app_tx_l), Prefs.textSize(this))
        styleGroup(listOf(R.id.app_md_weeks, R.id.app_md_agenda), currentWidgetMode())
        styleGroup(listOf(R.id.app_wk1, R.id.app_wk2, R.id.app_wk3, R.id.app_wk4), currentWidgetWeeks() - 1)

        findViewById<TextView>(R.id.app_opacity_label).text = "WIDGET OPACITY — ${Prefs.opacity(this)}%"
        for (id in listOf(R.id.app_sw_header, R.id.app_sw_monday, R.id.app_sw_strike)) {
            findViewById<Switch>(id).setTextColor(pal.ink)
        }
        findViewById<TextView>(R.id.app_widget_hdr).text =
            if (widgetIds().isNotEmpty()) "WIDGET LAYOUT — ALL WIDGETS"
            else "WIDGET LAYOUT — ADD A WIDGET TO USE"
        findViewById<TextView>(R.id.app_version_label).text =
            "Unbound ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_LABEL}"
    }

    private fun onGlobalSettingChanged() {
        // Re-render the app view and push to every widget; the panel stays open.
        refreshAll()
    }

    private fun widgetIds(): IntArray =
        AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, UnboundWidgetProvider::class.java))

    private fun currentWidgetWeeks(): Int =
        widgetIds().firstOrNull()?.let { Prefs.weeks(this, it) } ?: 2

    private fun currentWidgetMode(): Int =
        widgetIds().firstOrNull()?.let { Prefs.mode(this, it) } ?: 0

    private fun setWidgetWeeksAll(w: Int) {
        for (id in widgetIds()) Prefs.setWeeks(this, id, w)
        pokeWidgets()
    }

    private fun setWidgetModeAll(m: Int) {
        for (id in widgetIds()) Prefs.setMode(this, id, m)
        pokeWidgets()
    }

    private fun saveCals(box: LinearLayout) {
        val hidden = HashSet<String>()
        for (i in 0 until box.childCount) {
            val cb = box.getChildAt(i) as CheckBox
            if (!cb.isChecked) hidden.add(cb.tag as String)
        }
        Prefs.setHiddenCals(this, hidden)
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
    // weeks / agenda list
    // ---------------------------------------------------------------------

    private inner class WeeksAdapter : BaseAdapter() {

        // small LRU of rendered items; evicted bitmaps are left to the GC
        private val cache = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) =
                size > 16
        }

        fun clearCache() = cache.clear()

        override fun getCount(): Int = if (viewMode == 0) WEEK_COUNT else AGENDA_DAYS
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

            val today = LocalDate.now()
            val den = resources.displayMetrics.density
            val bmp: Bitmap
            val tappedDate: (Float, View) -> LocalDate

            if (viewMode == 0) {
                val minWeekH = maxOf(listH / 4, (110 * den).toInt())
                val ws = rangeStart.plusWeeks(position.toLong())
                bmp = cache[position] ?: WeekRenderer.renderWeek(
                    this@MainActivity, width, minWeekH, ws, today, events,
                    pal, Prefs.textScale(this@MainActivity), Prefs.strikePast(this@MainActivity),
                    isCurrentWeek = position == WEEKS_BACK, appCard = true, drawTopRule = false
                ).also { cache[position] = it }
                tappedDate = { x, v -> ws.plusDays((x / (v.width / 7f)).toInt().coerceIn(0, 6).toLong()) }
            } else {
                val d = today.plusDays(position.toLong())
                val minDayH = maxOf(listH / 8, (52 * den).toInt())
                bmp = cache[position] ?: AgendaRenderer.renderDay(
                    this@MainActivity, width, minDayH, d, today, events,
                    pal, Prefs.textScale(this@MainActivity), appCard = true
                ).also { cache[position] = it }
                tappedDate = { _, _ -> d }
            }
            iv.setImageBitmap(bmp)

            var downX = 0f
            var downY = 0f
            val slop = 24 * den
            iv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                            openDay(tappedDate(e.x, v))
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
