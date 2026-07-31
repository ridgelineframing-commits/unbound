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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hour-by-hour timeline for one day. Tap an event to edit; long-press + drag on
 * empty time to create a timed event; all-day events show in a strip up top.
 */
class DayTimelineActivity : Activity() {

    companion object {
        const val EXTRA_DATE_MS = "date_ms"
    }

    private var date: LocalDate = LocalDate.now()
    private lateinit var timeline: TimelineView
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val titleFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_timeline)

        val ms = intent?.getLongExtra(EXTRA_DATE_MS, System.currentTimeMillis()) ?: System.currentTimeMillis()
        date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

        timeline = TimelineView(this).apply {
            onEventTap = { ev ->
                startActivity(Intent(this@DayTimelineActivity, EventEditActivity::class.java)
                    .putExtra(EventEditActivity.EXTRA_EVENT_ID, ev.id)
                    .putExtra(EventEditActivity.EXTRA_INSTANCE_MS, ev.begin))
            }
            onCreateRange = { startMs, endMs ->
                startActivity(Intent(this@DayTimelineActivity, EventEditActivity::class.java)
                    .putExtra(EventEditActivity.EXTRA_START_MS, startMs)
                    .putExtra(EventEditActivity.EXTRA_END_MS, endMs))
            }
        }
        findViewById<FrameLayout>(R.id.day_timeline_holder).addView(timeline)

        findViewById<Button>(R.id.day_prev).setOnClickListener { date = date.minusDays(1); load() }
        findViewById<Button>(R.id.day_next).setOnClickListener { date = date.plusDays(1); load() }
        findViewById<Button>(R.id.day_add).setOnClickListener {
            startActivity(Intent(this, EventEditActivity::class.java).putExtra(
                EventEditActivity.EXTRA_DAY_MS,
                date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun load() {
        findViewById<TextView>(R.id.day_title).text = date.format(titleFmt)
        val pal = if (Prefs.resolveDark(this)) WeekRenderer.DARK else WeekRenderer.LIGHT
        findViewById<ScrollView>(R.id.day_scroll).setBackgroundColor(if (pal.dark) 0xFF14181F.toInt() else 0xFFF4F5F7.toInt())

        val granted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!granted) { timeline.setData(date, emptyList(), pal); return }

        val zone = ZoneId.systemDefault()
        val startMs = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hidden = Prefs.hiddenCals(this)
        uiScope.launch {
            val events = withContext(Dispatchers.IO) { CalendarRepository.events(this@DayTimelineActivity, startMs, endMs, hidden) }
            timeline.setData(date, events, pal)
            showAllDay(events, pal)
            // scroll to ~7am on first load of a day
            findViewById<ScrollView>(R.id.day_scroll).post {
                findViewById<ScrollView>(R.id.day_scroll).scrollTo(0, (7 * 60 * resources.displayMetrics.density).toInt())
            }
        }
    }

    private fun showAllDay(events: List<Ev>, pal: WeekRenderer.Palette) {
        val allDay = events.filter { ev ->
            ev.allDay && run {
                val s = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochMilli(ev.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                !date.isBefore(s) && !date.isAfter(last)
            }
        }
        val strip = findViewById<TextView>(R.id.day_allday)
        if (allDay.isEmpty()) { strip.visibility = android.view.View.GONE; return }
        strip.visibility = android.view.View.VISIBLE
        strip.setTextColor(pal.stone)
        strip.text = "All day: " + allDay.joinToString(", ") { if (it.title.isBlank()) "(no title)" else it.title }
    }
}
