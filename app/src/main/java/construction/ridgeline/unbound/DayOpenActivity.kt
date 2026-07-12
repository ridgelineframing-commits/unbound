package construction.ridgeline.unbound

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract

/**
 * Invisible trampoline: widget day-taps land here (explicit intent — required by
 * Android 14 for mutable PendingIntents), and we bounce straight into the
 * calendar app at that day.
 */
class DayOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val millis = intent?.getLongExtra(EXTRA_DAY_MS, -1L) ?: -1L
            if (millis > 0) {
                val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                ContentUris.appendId(builder, millis)
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setData(builder.build())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {
            // no calendar app — nothing sensible to do
        }
        finish()
    }

    companion object {
        const val EXTRA_DAY_MS = "day_ms"
    }
}
