package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Create / edit / delete a single calendar event on a device (writable) calendar.
 * Launched with EXTRA_EVENT_ID (-1 = new) and, for new events, EXTRA_DAY_MS (the
 * day tapped in the month view). Needs READ + WRITE_CALENDAR.
 */
class EventEditActivity : Activity() {

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_DAY_MS = "day_ms"
        const val EXTRA_END_DAY_MS = "end_day_ms"
        private const val REQ = 71
    }

    private val startCal = Calendar.getInstance()
    private val endCal = Calendar.getInstance()
    private var eventId = -1L
    private var cals: List<CalInfo> = emptyList()
    private var originalRrule = ""

    private val repeatRules = arrayOf<String?>(null, "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY", "FREQ=YEARLY")
    private val repeatLabels = listOf("Does not repeat", "Every day", "Every week", "Every month", "Every year")
    private val reminderMins = intArrayOf(-1, 0, 5, 10, 15, 30, 60, 1440)
    private val reminderLabels = listOf(
        "None", "At time of event", "5 minutes before", "10 minutes before",
        "15 minutes before", "30 minutes before", "1 hour before", "1 day before"
    )

    private val dateFmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_edit)
        eventId = intent?.getLongExtra(EXTRA_EVENT_ID, -1L) ?: -1L

        if (hasPerms()) init()
        else requestPermissions(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQ
        )
    }

    private fun hasPerms(): Boolean =
        checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasPerms()) init()
        else { toast("Calendar permission needed to edit events"); finish() }
    }

    private fun init() {
        cals = CalendarRepository.writableCalendars(this)
        if (cals.isEmpty()) { toast("No calendar you can add events to"); finish(); return }

        val spinner = whiteSpinner(R.id.ev_calendar, cals.map { it.name })
        val reminder = whiteSpinner(R.id.ev_reminder, reminderLabels)
        val allday = findViewById<Switch>(R.id.ev_allday)

        val detail = if (eventId != -1L) CalendarRepository.eventById(this, eventId) else null
        originalRrule = detail?.rrule ?: ""
        val repeatClass = RecurrenceUtil.classify(originalRrule)
        // Add a "Custom" entry only when the event has a rule we can't round-trip,
        // so a complex RRULE is preserved instead of silently downgraded.
        val repeat = whiteSpinner(
            R.id.ev_repeat,
            if (repeatClass == RecurrenceUtil.Repeat.CUSTOM) repeatLabels + "Custom (keep as-is)" else repeatLabels
        )

        if (detail != null) {
            findViewById<TextView>(R.id.ev_header).text = "Edit event"
            findViewById<EditText>(R.id.ev_title).setText(detail.title)
            findViewById<EditText>(R.id.ev_location).setText(detail.location)
            findViewById<EditText>(R.id.ev_notes).setText(detail.description)
            allday.isChecked = detail.allDay
            if (detail.allDay) {
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = detail.begin }
                startCal.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 9, 0, 0)
                val ue = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = detail.end - 1 }
                endCal.set(ue.get(Calendar.YEAR), ue.get(Calendar.MONTH), ue.get(Calendar.DAY_OF_MONTH), 10, 0, 0)
            } else {
                startCal.timeInMillis = detail.begin
                endCal.timeInMillis = detail.end
            }
            val idx = cals.indexOfFirst { it.id == detail.calId }
            if (idx >= 0) spinner.setSelection(idx)
            repeat.setSelection(
                when (repeatClass) {
                    RecurrenceUtil.Repeat.DAILY -> 1
                    RecurrenceUtil.Repeat.WEEKLY -> 2
                    RecurrenceUtil.Repeat.MONTHLY -> 3
                    RecurrenceUtil.Repeat.YEARLY -> 4
                    RecurrenceUtil.Repeat.CUSTOM -> repeatLabels.size // the appended "Custom" entry
                    else -> 0
                }
            )
            val mi = reminderMins.indexOf(detail.reminderMinutes)
            reminder.setSelection(if (mi >= 0) mi else 0)
            findViewById<Button>(R.id.ev_delete).apply {
                visibility = View.VISIBLE
                setOnClickListener { confirmDelete() }
            }
        } else {
            val dayMs = intent?.getLongExtra(EXTRA_DAY_MS, System.currentTimeMillis()) ?: System.currentTimeMillis()
            val endDayMs = intent?.getLongExtra(EXTRA_END_DAY_MS, -1L) ?: -1L
            if (endDayMs > 0 && endDayMs != dayMs) {
                // dragged across days in the month view -> all-day event spanning the range
                allday.isChecked = true
                startCal.timeInMillis = minOf(dayMs, endDayMs)
                endCal.timeInMillis = maxOf(dayMs, endDayMs)
            } else {
                startCal.timeInMillis = dayMs
                startCal.set(Calendar.HOUR_OF_DAY, 9); startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0); startCal.set(Calendar.MILLISECOND, 0)
                endCal.timeInMillis = startCal.timeInMillis + 60L * 60 * 1000
            }
        }

        allday.setOnCheckedChangeListener { _, on -> applyAllDay(on) }
        applyAllDay(allday.isChecked)

        findViewById<Button>(R.id.ev_start_date).setOnClickListener { pickDate(startCal, true) }
        findViewById<Button>(R.id.ev_start_time).setOnClickListener { pickTime(startCal, true) }
        findViewById<Button>(R.id.ev_end_date).setOnClickListener { pickDate(endCal, false) }
        findViewById<Button>(R.id.ev_end_time).setOnClickListener { pickTime(endCal, false) }
        findViewById<Button>(R.id.ev_save).setOnClickListener { save() }
        refreshLabels()
    }

    private fun applyAllDay(allDay: Boolean) {
        val vis = if (allDay) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.ev_start_time).visibility = vis
        findViewById<Button>(R.id.ev_end_time).visibility = vis
    }

    private fun pickDate(cal: Calendar, isStart: Boolean) {
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            keepOrder(isStart); refreshLabels()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime(cal: Calendar, isStart: Boolean) {
        TimePickerDialog(this, { _, h, min ->
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            keepOrder(isStart); refreshLabels()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }

    /** Keep end >= start whichever side was just edited. */
    private fun keepOrder(editedStart: Boolean) {
        if (endCal.before(startCal)) {
            if (editedStart) endCal.timeInMillis = startCal.timeInMillis + 60L * 60 * 1000
            else startCal.timeInMillis = endCal.timeInMillis - 60L * 60 * 1000
        }
    }

    private fun refreshLabels() {
        findViewById<Button>(R.id.ev_start_date).text = dateFmt.format(startCal.time)
        findViewById<Button>(R.id.ev_start_time).text = timeFmt.format(startCal.time)
        findViewById<Button>(R.id.ev_end_date).text = dateFmt.format(endCal.time)
        findViewById<Button>(R.id.ev_end_time).text = timeFmt.format(endCal.time)
    }

    private fun save() {
        val title = findViewById<EditText>(R.id.ev_title).text.toString().trim()
        if (title.isEmpty()) { toast("Add a title"); return }
        val allDay = findViewById<Switch>(R.id.ev_allday).isChecked
        val calId = cals[findViewById<Spinner>(R.id.ev_calendar).selectedItemPosition].id
        val location = findViewById<EditText>(R.id.ev_location).text.toString()
        val notes = findViewById<EditText>(R.id.ev_notes).text.toString()
        val ri = findViewById<Spinner>(R.id.ev_repeat).selectedItemPosition
        val rrule = if (ri < repeatRules.size) repeatRules[ri] else originalRrule // "Custom" keeps the original
        val reminder = reminderMins[findViewById<Spinner>(R.id.ev_reminder).selectedItemPosition]

        val begin: Long
        val end: Long
        if (allDay) {
            val startDate = LocalDate.of(
                startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH) + 1, startCal.get(Calendar.DAY_OF_MONTH)
            )
            val endRaw = LocalDate.of(
                endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH) + 1, endCal.get(Calendar.DAY_OF_MONTH)
            )
            val lastDate = if (endRaw.isBefore(startDate)) startDate else endRaw
            begin = AllDayUtil.startMillis(startDate)
            end = AllDayUtil.endMillisExclusive(lastDate)
        } else {
            begin = startCal.timeInMillis
            end = if (endCal.timeInMillis <= begin) begin + 60L * 60 * 1000 else endCal.timeInMillis
        }

        val ok = if (eventId == -1L)
            CalendarRepository.insertEvent(this, calId, title, begin, end, allDay, location, notes, rrule, reminder) != null
        else
            CalendarRepository.updateEvent(this, eventId, calId, title, begin, end, allDay, location, notes, rrule, reminder)

        if (ok) {
            UnboundWidgetProvider.updateAll(this)
            setResult(RESULT_OK)
            finish()
        } else {
            toast("Couldn't save the event")
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete event?")
            .setMessage("This removes it from your calendar.")
            .setPositiveButton("Delete") { _, _ ->
                if (CalendarRepository.deleteEvent(this, eventId)) {
                    UnboundWidgetProvider.updateAll(this)
                    setResult(RESULT_OK)
                    finish()
                } else toast("Couldn't delete the event")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun whiteSpinner(id: Int, labels: List<String>): Spinner {
        val sp = findViewById<Spinner>(id)
        val a = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply { setTextColor(0xFFF4F5F7.toInt()) }
        }
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = a
        return sp
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
