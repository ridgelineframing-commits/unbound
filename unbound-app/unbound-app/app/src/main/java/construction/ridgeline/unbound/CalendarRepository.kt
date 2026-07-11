package construction.ridgeline.unbound

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract

data class CalInfo(val id: Long, val name: String, val color: Int)
data class Ev(
    val id: Long,
    val calId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int
)

object CalendarRepository {

    fun calendars(c: Context): List<CalInfo> {
        val out = ArrayList<CalInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR
        )
        try {
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(CalInfo(cur.getLong(0), cur.getString(1) ?: "Calendar", cur.getInt(2)))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun events(c: Context, startMs: Long, endMs: Long, calFilter: Set<String>?): List<Ev> {
        val out = ArrayList<Ev>()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_COLOR
        )
        try {
            c.contentResolver.query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val calId = cur.getLong(1)
                    if (calFilter != null && !calFilter.contains(calId.toString())) continue
                    out.add(
                        Ev(
                            id = cur.getLong(0),
                            calId = calId,
                            title = cur.getString(2) ?: "(no title)",
                            begin = cur.getLong(3),
                            end = cur.getLong(4),
                            allDay = cur.getInt(5) == 1,
                            color = cur.getInt(6)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
