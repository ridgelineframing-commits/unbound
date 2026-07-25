package construction.ridgeline.unbound

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Locale

/**
 * Title search across the device calendars over a wide window (roughly the last
 * six months to eighteen months out). Tapping a result opens it in the editor.
 */
class SearchActivity : Activity() {

    private val results = ArrayList<Ev>()
    private lateinit var adapter: ResultsAdapter
    private val timeFmt = SimpleDateFormat("EEE, MMM d yyyy · h:mm a", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        adapter = ResultsAdapter()
        findViewById<ListView>(R.id.search_results).adapter = adapter
        findViewById<ListView>(R.id.search_results).setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, EventEditActivity::class.java)
                .putExtra(EventEditActivity.EXTRA_EVENT_ID, results[pos].id))
        }
        val input = findViewById<EditText>(R.id.search_input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { runSearch(s?.toString().orEmpty()) }
        })
    }

    override fun onResume() {
        super.onResume()
        // returning from the editor may have changed results
        runSearch(findViewById<EditText>(R.id.search_input).text.toString())
    }

    private fun runSearch(query: String) {
        val status = findViewById<TextView>(R.id.search_status)
        val q = query.trim()
        if (q.length < 2) {
            results.clear(); adapter.notifyDataSetChanged()
            status.text = "Type at least two letters."
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Grant calendar access in the app first."
            return
        }
        val zone = ZoneId.systemDefault()
        val now = java.time.LocalDate.now(zone)
        val startMs = now.minusMonths(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = now.plusMonths(18).atStartOfDay(zone).toInstant().toEpochMilli()
        val hidden = Prefs.hiddenCals(this)
        Thread {
            val found = CalendarRepository.searchEvents(this, q, startMs, endMs, hidden)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                results.clear(); results.addAll(found)
                adapter.notifyDataSetChanged()
                status.text = if (found.isEmpty()) "No matches." else "${found.size} match${if (found.size == 1) "" else "es"}"
            }
        }.start()
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(p: Int) = results[p]
        override fun getItemId(p: Int) = results[p].id
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val row = (cv as? LinearLayout) ?: LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (14 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            row.removeAllViews()
            val ev = results[pos]
            val title = TextView(this@SearchActivity).apply {
                text = if (ev.title.isBlank()) "(no title)" else ev.title
                setTextColor(0xFFF4F5F7.toInt()); textSize = 16f
            }
            val when_ = TextView(this@SearchActivity).apply {
                text = if (ev.allDay) dayFmt.format(ev.begin) + " · all day" else timeFmt.format(ev.begin)
                setTextColor(0xFF9AA0AA.toInt()); textSize = 13f
            }
            row.addView(title); row.addView(when_)
            return row
        }
    }
}
