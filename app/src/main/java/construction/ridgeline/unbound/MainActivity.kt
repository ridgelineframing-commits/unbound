package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var viewMode = 0 // 0 = weeks, 1 = agenda, 2 = month (app-local)

    // interactive month view state
    private var displayedMonth: LocalDate = LocalDate.now().withDayOfMonth(1)
    private var selectedDay: LocalDate = LocalDate.now()
    private var monthEvents: List<Ev> = emptyList()
    private var gridStartField: LocalDate = LocalDate.now()
    private var gridRows = 0
    private var dragStartDate: LocalDate? = null

    // Main-thread scope for UI-bound loads; cancelled in onDestroy so nothing
    // touches a dead Activity. A cancellable monthJob replaces the old stale-token.
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monthJob: Job? = null
    private val dayEvents = ArrayList<Ev>()
    private lateinit var dayEventsAdapter: DayEventsAdapter
    private val dayTimeFmt = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())

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
        findViewById<TextView>(R.id.seg_month).setOnClickListener { setMode(2) }

        findViewById<ImageButton>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        dayEventsAdapter = DayEventsAdapter()
        findViewById<ListView>(R.id.day_events).adapter = dayEventsAdapter
        findViewById<ListView>(R.id.day_events).setOnItemClickListener { _, _, pos, _ ->
            openEditor(dayEvents[pos].id, null)
        }
        findViewById<Button>(R.id.btn_month_prev).setOnClickListener { stepMonth(-1) }
        findViewById<Button>(R.id.btn_month_next).setOnClickListener { stepMonth(1) }
        findViewById<Button>(R.id.add_event_btn).setOnClickListener {
            openEditor(-1L, selectedDay)
        }

        // Tap a day to select it; drag across days to create an all-day event.
        findViewById<LinearLayout>(R.id.month_grid).setOnTouchListener { v, e ->
            val w = v.width
            if (w == 0 || gridRows == 0) return@setOnTouchListener false
            val rowH = dpi(52f)
            fun dateAt(x: Float, y: Float): LocalDate {
                val col = (x / (w / 7f)).toInt().coerceIn(0, 6)
                val row = (y / rowH).toInt().coerceIn(0, gridRows - 1)
                return gridStartField.plusDays((row * 7 + col).toLong())
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragStartDate = dateAt(e.x, e.y); true }
                MotionEvent.ACTION_UP -> {
                    val start = dragStartDate
                    dragStartDate = null
                    if (start != null) {
                        val end = dateAt(e.x, e.y)
                        if (start == end) selectDay(start)
                        else openRangeEditor(minOf(start, end), maxOf(start, end))
                    }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragStartDate = null; true }
                else -> true
            }
        }

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
        uiScope.cancel()
        adapter.clearCache()
    }

    private fun granted(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun setMode(m: Int) {
        if (viewMode == m) return
        viewMode = m
        styleSegments()
        applyViewModeVisibility()
        if (m == 2) {
            selectedDay = LocalDate.now()
            displayedMonth = selectedDay.withDayOfMonth(1)
            showMonth()
        } else {
            adapter.clearCache()
            adapter.notifyDataSetChanged()
            listView.setSelection(if (m == 0) WEEKS_BACK else 0)
        }
    }

    private fun applyViewModeVisibility() {
        listView.visibility = if (viewMode == 2) View.GONE else View.VISIBLE
        findViewById<View>(R.id.month_container).visibility = if (viewMode == 2) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------
    // data + theme
    // ---------------------------------------------------------------------

    private fun refreshAll() {
        pal = if (Prefs.resolveDark(this)) WeekRenderer.DARK else WeekRenderer.LIGHT
        applyChrome()
        applyViewModeVisibility()

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
        uiScope.launch {
            val evs = withContext(Dispatchers.IO) { CalendarRepository.events(this@MainActivity, startMs, endMs, hidden) }
            events = evs
            adapter.clearCache()
            adapter.notifyDataSetChanged()
            if (listView.firstVisiblePosition == 0 && viewMode == 0) {
                listView.setSelection(WEEKS_BACK)
            }
        }
        if (viewMode == 2) showMonth()
        pokeWidgets()
    }

    // ---------------------------------------------------------------------
    // interactive month view
    // ---------------------------------------------------------------------

    private fun dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun stepMonth(delta: Int) {
        displayedMonth = displayedMonth.plusMonths(delta.toLong())
        val today = LocalDate.now()
        selectedDay = if (today.year == displayedMonth.year && today.month == displayedMonth.month)
            today else displayedMonth
        showMonth()
    }

    /** Load the events for the displayed month's grid, then (re)build it. */
    private fun showMonth() {
        val firstDow = if (Prefs.weekStartsMonday(this)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        val gridStart = displayedMonth.with(TemporalAdjusters.previousOrSame(firstDow))
        val monthEnd = displayedMonth.withDayOfMonth(displayedMonth.lengthOfMonth())
        val gridEnd = monthEnd.with(TemporalAdjusters.nextOrSame(firstDow.plus(6L)))
        val zone = ZoneId.systemDefault()
        val startMs = gridStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = gridEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hidden = Prefs.hiddenCals(this)
        val ok = granted()
        monthJob?.cancel()
        monthJob = uiScope.launch {
            val evs = if (ok) withContext(Dispatchers.IO) {
                CalendarRepository.events(this@MainActivity, startMs, endMs, hidden)
            } else emptyList()
            if (viewMode != 2) return@launch
            monthEvents = evs
            if (selectedDay.year != displayedMonth.year || selectedDay.month != displayedMonth.month) {
                val today = LocalDate.now()
                selectedDay = if (today.month == displayedMonth.month && today.year == displayedMonth.year)
                    today else displayedMonth
            }
            buildMonthGrid(firstDow, gridStart, gridEnd)
            updateSelectedDayUi()
        }
    }

    private fun selectDay(date: LocalDate) {
        if (date.month != displayedMonth.month || date.year != displayedMonth.year) {
            displayedMonth = date.withDayOfMonth(1)
            selectedDay = date
            showMonth()
            return
        }
        selectedDay = date
        val firstDow = if (Prefs.weekStartsMonday(this)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        val gridStart = displayedMonth.with(TemporalAdjusters.previousOrSame(firstDow))
        val monthEnd = displayedMonth.withDayOfMonth(displayedMonth.lengthOfMonth())
        val gridEnd = monthEnd.with(TemporalAdjusters.nextOrSame(firstDow.plus(6L)))
        buildMonthGrid(firstDow, gridStart, gridEnd)
        updateSelectedDayUi()
    }

    private fun eventColorsByDate(gridStart: LocalDate, gridEnd: LocalDate): Map<LocalDate, List<Int>> {
        val zone = ZoneId.systemDefault()
        val out = HashMap<LocalDate, ArrayList<Int>>()
        fun add(d: LocalDate, c: Int) {
            if (d.isBefore(gridStart) || d.isAfter(gridEnd)) return
            val l = out.getOrPut(d) { ArrayList() }
            if (l.size < 3 && !l.contains(c)) l.add(c)
        }
        for (ev in monthEvents) {
            val c = if (ev.color == 0) pal.defaultEv else ev.color
            if (ev.allDay) {
                val s = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochMilli(ev.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                var d = if (s.isBefore(gridStart)) gridStart else s
                val end = if (last.isAfter(gridEnd)) gridEnd else last
                while (!d.isAfter(end)) { add(d, c); d = d.plusDays(1) }
            } else {
                add(Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalDate(), c)
            }
        }
        return out
    }

    private fun buildMonthGrid(firstDow: DayOfWeek, gridStart: LocalDate, gridEnd: LocalDate) {
        val today = LocalDate.now()
        findViewById<TextView>(R.id.month_nav_label).apply {
            text = "${displayedMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${displayedMonth.year}"
            setTextColor(pal.ink)
        }
        findViewById<Button>(R.id.btn_month_prev).setTextColor(pal.stone)
        findViewById<Button>(R.id.btn_month_next).setTextColor(pal.stone)

        val dowIds = intArrayOf(R.id.mdow0, R.id.mdow1, R.id.mdow2, R.id.mdow3, R.id.mdow4, R.id.mdow5, R.id.mdow6)
        for (i in 0..6) {
            findViewById<TextView>(dowIds[i]).apply {
                text = firstDow.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    .uppercase(Locale.getDefault())
                setTextColor(pal.faint)
            }
        }

        val colors = eventColorsByDate(gridStart, gridEnd)
        val grid = findViewById<LinearLayout>(R.id.month_grid)
        grid.removeAllViews()
        val rows = ((ChronoUnit.DAYS.between(gridStart, gridEnd) + 1) / 7).toInt()
        gridStartField = gridStart
        gridRows = rows
        var idx = 0
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(52f))
            }
            for (c in 0..6) {
                row.addView(makeCell(gridStart.plusDays(idx.toLong()), today, colors))
                idx++
            }
            grid.addView(row)
        }
    }

    private fun makeCell(date: LocalDate, today: LocalDate, colors: Map<LocalDate, List<Int>>): View {
        val inMonth = date.month == displayedMonth.month && date.year == displayedMonth.year
        val isToday = date == today
        val isSel = date == selectedDay
        val isPast = date.isBefore(today)

        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).also { it.weight = 1f }
            setPadding(0, dpi(5f), 0, 0)
            // taps + drags are handled on the grid container (see onCreate) so a
            // drag can span cells; individual cells don't take clicks.
            if (isSel && !isToday) {
                background = GradientDrawable().apply {
                    cornerRadius = dpi(10f).toFloat()
                    setColor((0x33 shl 24) or (pal.todayPill and 0x00FFFFFF))
                }
            }
        }

        val d = dpi(26f)
        val num = TextView(this).apply {
            text = date.dayOfMonth.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(d, d)
            if (isToday) {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.todayPill) }
                setTextColor(pal.todayPillText)
            } else {
                setTextColor(when { !inMonth -> pal.faint; isPast -> pal.pastText; else -> pal.ink })
                if (isPast && inMonth && Prefs.strikePast(this@MainActivity)) {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
            }
        }
        cell.addView(num)

        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpi(3f) }
        }
        if (inMonth) colors[date]?.forEach { col ->
            dots.addView(View(this).apply {
                val sz = dpi(5f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginStart = dpi(1.5f); it.marginEnd = dpi(1.5f) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isPast) (0x99 shl 24) or (col and 0x00FFFFFF) else col)
                }
            })
        }
        cell.addView(dots)
        return cell
    }

    private fun updateSelectedDayUi() {
        findViewById<TextView>(R.id.sel_day_label).apply {
            text = selectedDay.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
            setTextColor(pal.ink)
        }
        dayEvents.clear()
        dayEvents.addAll(eventsOnDate(selectedDay))
        dayEventsAdapter.notifyDataSetChanged()
    }

    private fun eventsOnDate(date: LocalDate): List<Ev> {
        val zone = ZoneId.systemDefault()
        return monthEvents.filter { ev ->
            if (ev.allDay) {
                val s = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochMilli(ev.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                !date.isBefore(s) && !date.isAfter(last)
            } else {
                Instant.ofEpochMilli(ev.begin).atZone(zone).toLocalDate() == date
            }
        }.sortedBy { it.begin }
    }

    private fun openEditor(eventId: Long, day: LocalDate?) {
        val i = Intent(this, EventEditActivity::class.java)
        if (eventId != -1L) i.putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
        if (day != null) i.putExtra(
            EventEditActivity.EXTRA_DAY_MS,
            day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        startActivity(i)
    }

    private fun openRangeEditor(start: LocalDate, end: LocalDate) {
        val zone = ZoneId.systemDefault()
        startActivity(
            Intent(this, EventEditActivity::class.java)
                .putExtra(EventEditActivity.EXTRA_DAY_MS, start.atStartOfDay(zone).toInstant().toEpochMilli())
                .putExtra(EventEditActivity.EXTRA_END_DAY_MS, end.atStartOfDay(zone).toInstant().toEpochMilli())
        )
    }

    private inner class DayEventsAdapter : BaseAdapter() {
        override fun getCount() = dayEvents.size
        override fun getItem(p: Int) = dayEvents[p]
        override fun getItemId(p: Int) = dayEvents[p].id
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val ev = dayEvents[pos]
            val row = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpi(4f), dpi(10f), dpi(4f), dpi(10f))
                gravity = Gravity.CENTER_VERTICAL
            }
            row.removeAllViews()
            val bar = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dpi(4f), dpi(34f)).also { it.marginEnd = dpi(10f) }
                background = GradientDrawable().apply {
                    cornerRadius = dpi(2f).toFloat()
                    setColor(if (ev.color == 0) pal.defaultEv else ev.color)
                }
            }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@MainActivity).apply {
                text = if (ev.title.isBlank()) "(no title)" else ev.title
                setTextColor(pal.ink); textSize = 15f
            })
            texts.addView(TextView(this@MainActivity).apply {
                text = if (ev.allDay) "All day" else dayTimeFmt.format(ev.begin)
                setTextColor(pal.stone); textSize = 12f
            })
            row.addView(bar); row.addView(texts)
            return row
        }
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
        val month = findViewById<TextView>(R.id.seg_month)
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
        style(month, viewMode == 2)
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
        findViewById<ImageButton>(R.id.btn_search)
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
        uiScope.launch {
            val counts = withContext(Dispatchers.IO) {
                CalendarRepository.instanceCounts(this@MainActivity, nowMs, nowMs + 30L * 24 * 60 * 60 * 1000)
            }
            val cals = withContext(Dispatchers.IO) { CalendarRepository.calendars(this@MainActivity) }
            if (!granted()) return@launch
            val hidden = Prefs.hiddenCals(this@MainActivity)
            box.removeAllViews()
            for (c in cals) {
                val cb = CheckBox(this@MainActivity)
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
        listOf(R.id.app_md_weeks, R.id.app_md_agenda, R.id.app_md_month)
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
        styleGroup(listOf(R.id.app_md_weeks, R.id.app_md_agenda, R.id.app_md_month), currentWidgetMode())
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
