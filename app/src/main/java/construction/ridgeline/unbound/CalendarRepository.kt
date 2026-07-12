package construction.ridgeline.unbound

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract

data class CalInfo(
    val id: Long,
    val name: String,
    val color: Int,
    val syncOn: Boolean,
    val visible: Boolean,
    val account: String
)
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
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        try {
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(
                        CalInfo(
                            id = cur.getLong(0),
                            name = cur.getString(1) ?: "Calendar",
                            color = cur.getInt(2),
                            syncOn = cur.getInt(3) == 1,
                            visible = cur.getInt(4) == 1,
                            account = cur.getString(5) ?: ""
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** How many event instances each calendar actually has ON THE DEVICE in [startMs, endMs).
     *  Ground truth for "is this calendar really synced" — the Google Calendar app can show
     *  network-fetched events that were never written to the device database. */
    fun instanceCounts(c: Context, startMs: Long, endMs: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)
        try {
            c.contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances.CALENDAR_ID),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    out[id] = (out[id] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun events(c: Context, startMs: Long, endMs: Long, hiddenCals: Set<String>): List<Ev> {
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
                    if (hiddenCals.contains(calId.toString())) continue
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
