package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Seven-day timeline (Week view). Reuses the multi-column TimelineView. */
class WeekTimelineActivity : Activity() {

    companion object {
        const val EXTRA_DATE_MS = "date_ms"
    }

    private var weekStart: LocalDate = LocalDate.now()
    private lateinit var timeline: TimelineView
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val titleFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val dayIds = intArrayOf(R.id.wkd0, R.id.wkd1, R.id.wkd2, R.id.wkd3, R.id.wkd4, R.id.wkd5, R.id.wkd6)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_week_timeline)

        val ms = intent?.getLongExtra(EXTRA_DATE_MS, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        weekStart = startOfWeek(date)

        timeline = TimelineView(this).apply {
            onEventTap = { ev ->
                startActivity(Intent(this@WeekTimelineActivity, EventEditActivity::class.java)
                    .putExtra(EventEditActivity.EXTRA_EVENT_ID, ev.id)
                    .putExtra(EventEditActivity.EXTRA_INSTANCE_MS, ev.begin))
            }
            onCreateRange = { startMs, endMs ->
                startActivity(Intent(this@WeekTimelineActivity, EventEditActivity::class.java)
                    .putExtra(EventEditActivity.EXTRA_START_MS, startMs)
                    .putExtra(EventEditActivity.EXTRA_END_MS, endMs))
            }
        }
        findViewById<FrameLayout>(R.id.wk_timeline_holder).addView(timeline)

        findViewById<Button>(R.id.wk_prev).setOnClickListener { weekStart = weekStart.minusWeeks(1); load() }
        findViewById<Button>(R.id.wk_next).setOnClickListener { weekStart = weekStart.plusWeeks(1); load() }
    }

    override fun onResume() { super.onResume(); load() }
    override fun onDestroy() { super.onDestroy(); uiScope.cancel() }

    private fun startOfWeek(date: LocalDate): LocalDate {
        val firstDow = if (Prefs.weekStartsMonday(this)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        return date.with(TemporalAdjusters.previousOrSame(firstDow))
    }

    private fun load() {
        val days = (0..6).map { weekStart.plusDays(it.toLong()) }
        val pal = if (Prefs.resolveDark(this)) WeekRenderer.DARK else WeekRenderer.LIGHT
        findViewById<ScrollView>(R.id.wk_scroll).setBackgroundColor(if (pal.dark) 0xFF14181F.toInt() else 0xFFF4F5F7.toInt())
        val today = LocalDate.now()
        findViewById<TextView>(R.id.wk_title).text = "${weekStart.format(titleFmt)} – ${days[6].format(titleFmt)}"
        for (i in 0..6) {
            val d = days[i]
            findViewById<TextView>(dayIds[i]).apply {
                text = "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault())}\n${d.dayOfMonth}"
                setTextColor(if (d == today) pal.todayPill else pal.stone)
                setOnClickListener {
                    startActivity(Intent(this@WeekTimelineActivity, DayTimelineActivity::class.java)
                        .putExtra(DayTimelineActivity.EXTRA_DATE_MS, d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                }
            }
        }

        val granted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!granted) { timeline.setData(days, emptyList(), pal); return }
        val zone = ZoneId.systemDefault()
        val startMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val hidden = Prefs.hiddenCals(this)
        uiScope.launch {
            val events = withContext(Dispatchers.IO) { CalendarRepository.events(this@WeekTimelineActivity, startMs, endMs, hidden) }
            timeline.setData(days, events, pal)
            findViewById<ScrollView>(R.id.wk_scroll).post {
                findViewById<ScrollView>(R.id.wk_scroll).scrollTo(0, (7 * 56 * resources.displayMetrics.density).toInt())
            }
        }
    }
}
