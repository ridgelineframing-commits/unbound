package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class ConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val selBg = 0xFF1B1B19.toInt()
    private val selFg = 0xFFFFFFFF.toInt()
    private val unselBg = 0xFFE4E3DE.toInt()
    private val unselFg = 0xFF1B1B19.toInt()

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

        setupMode()
        setupWeeks()
        setupTheme()
        setupOpacity()
        setupTextSize()
        setupToggles()
        setupCalendars()

        findViewById<Button>(R.id.btn_done).setOnClickListener {
            apply()
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            )
            finish()
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun style(group: List<Button>, selected: Int) {
        group.forEachIndexed { i, b ->
            if (i == selected) {
                b.setBackgroundColor(selBg); b.setTextColor(selFg)
            } else {
                b.setBackgroundColor(unselBg); b.setTextColor(unselFg)
            }
        }
    }

    private fun apply() {
        UnboundWidgetProvider.updateAll(this)
    }

    // ---- sections ------------------------------------------------------------

    private fun setupMode() {
        val btns = listOf(R.id.md_weeks, R.id.md_agenda).map { findViewById<Button>(it) }
        style(btns, Prefs.mode(this, widgetId))
        btns.forEachIndexed { i, b ->
            b.setOnClickListener {
                Prefs.setMode(this, widgetId, i)
                style(btns, i)
                apply()
            }
        }
    }

    private fun setupWeeks() {
        val btns = listOf(R.id.wk1, R.id.wk2, R.id.wk3, R.id.wk4).map { findViewById<Button>(it) }
        style(btns, Prefs.weeks(this, widgetId) - 1)
        btns.forEachIndexed { i, b ->
            b.setOnClickListener {
                Prefs.setWeeks(this, widgetId, i + 1)
                style(btns, i)
                apply()
            }
        }
    }

    private fun setupTheme() {
        val btns = listOf(R.id.th_auto, R.id.th_light, R.id.th_dark).map { findViewById<Button>(it) }
        style(btns, Prefs.theme(this))
        btns.forEachIndexed { i, b ->
            b.setOnClickListener {
                Prefs.setTheme(this, i)
                style(btns, i)
                apply()
            }
        }
    }

    private fun setupOpacity() {
        val seek = findViewById<SeekBar>(R.id.opacity_seek)
        val label = findViewById<TextView>(R.id.opacity_label)
        // seek 0..80 maps to opacity 20..100
        val cur = Prefs.opacity(this)
        seek.progress = cur - 20
        label.text = "BACKGROUND OPACITY — $cur%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "BACKGROUND OPACITY — ${progress + 20}%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Prefs.setOpacity(this@ConfigActivity, (sb?.progress ?: 80) + 20)
                apply()
            }
        })
    }

    private fun setupTextSize() {
        val btns = listOf(R.id.tx_s, R.id.tx_m, R.id.tx_l).map { findViewById<Button>(it) }
        style(btns, Prefs.textSize(this))
        btns.forEachIndexed { i, b ->
            b.setOnClickListener {
                Prefs.setTextSize(this, i)
                style(btns, i)
                apply()
            }
        }
    }

    private fun setupToggles() {
        val header = findViewById<Switch>(R.id.sw_header)
        header.isChecked = Prefs.showHeader(this)
        header.setOnCheckedChangeListener { _, on ->
            Prefs.setShowHeader(this, on)
            apply()
        }

        val monday = findViewById<Switch>(R.id.sw_monday)
        monday.isChecked = Prefs.weekStartsMonday(this)
        monday.setOnCheckedChangeListener { _, on ->
            Prefs.setWeekStartsMonday(this, on)
            apply()
        }

        val strike = findViewById<Switch>(R.id.sw_strike)
        strike.isChecked = Prefs.strikePast(this)
        strike.setOnCheckedChangeListener { _, on ->
            Prefs.setStrikePast(this, on)
            apply()
        }
    }

    private fun setupCalendars() {
        val hint = findViewById<TextView>(R.id.cal_hint)
        val box = findViewById<LinearLayout>(R.id.cal_list)
        val granted = checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        hint.text = "Choose which calendars appear in the widget."
        val hidden = Prefs.hiddenCals(this)
        for (c in CalendarRepository.calendars(this)) {
            val cb = CheckBox(this)
            cb.text = c.name
            cb.setTextColor(0xFF1B1B19.toInt())
            cb.isChecked = !hidden.contains(c.id.toString())
            cb.tag = c.id.toString()
            cb.setOnCheckedChangeListener { _, _ -> saveCals(box) }
            box.addView(cb)
        }
    }

    private fun saveCals(box: LinearLayout) {
        val hidden = HashSet<String>()
        for (i in 0 until box.childCount) {
            val cb = box.getChildAt(i) as CheckBox
            if (!cb.isChecked) hidden.add(cb.tag as String)
        }
        Prefs.setHiddenCals(this, hidden)
        apply()
    }
}
