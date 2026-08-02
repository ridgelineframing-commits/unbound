package construction.ridgeline.unbound

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible trampoline: widget day-taps land here (explicit intent — required by
 * Android 14 for mutable PendingIntents), and we bounce into Unbound's own Day
 * timeline for that day (rather than out to the system calendar app).
 */
class DayOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val millis = intent?.getLongExtra(EXTRA_DAY_MS, -1L) ?: -1L
            if (millis > 0) {
                startActivity(
                    Intent(this, DayTimelineActivity::class.java)
                        .putExtra(DayTimelineActivity.EXTRA_DATE_MS, millis)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {
            // nothing sensible to do
        }
        finish()
    }

    companion object {
        const val EXTRA_DAY_MS = "day_ms"
    }
}
