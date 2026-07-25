package construction.ridgeline.unbound

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

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
/** Full detail for a single event, used by the editor. */
data class EventDetail(
    val id: Long,
    val calId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val description: String,
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

    /** Instances in [startMs, endMs) whose title matches [query] (case-insensitive), oldest first. */
    fun searchEvents(c: Context, query: String, startMs: Long, endMs: Long, hiddenCals: Set<String>): List<Ev> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
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
        val out = ArrayList<Ev>()
        try {
            c.contentResolver.query(
                builder.build(), proj,
                "${CalendarContract.Instances.TITLE} LIKE ?", arrayOf("%$q%"),
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val calId = cur.getLong(1)
                    if (hiddenCals.contains(calId.toString())) continue
                    out.add(
                        Ev(cur.getLong(0), calId, cur.getString(2) ?: "(no title)",
                            cur.getLong(3), cur.getLong(4), cur.getInt(5) == 1, cur.getInt(6))
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---- write side (needs WRITE_CALENDAR) ----------------------------------

    /** Calendars this app may add events to (contributor access or better). */
    fun writableCalendars(c: Context): List<CalInfo> {
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
            c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(CalInfo(cur.getLong(0), cur.getString(1) ?: "Calendar", cur.getInt(2),
                        cur.getInt(3) == 1, cur.getInt(4) == 1, cur.getString(5) ?: ""))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun eventById(c: Context, eventId: Long): EventDetail? {
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DISPLAY_COLOR
        )
        try {
            c.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                proj, null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    return EventDetail(
                        id = cur.getLong(0),
                        calId = cur.getLong(1),
                        title = cur.getString(2) ?: "",
                        begin = cur.getLong(3),
                        end = if (cur.isNull(4)) cur.getLong(3) else cur.getLong(4),
                        allDay = cur.getInt(5) == 1,
                        location = cur.getString(6) ?: "",
                        description = cur.getString(7) ?: "",
                        color = cur.getInt(8)
                    )
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun eventValues(
        calId: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?
    ) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, begin)
        put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        // All-day events are stored against UTC midnight per CalendarContract.
        val tz = if (allDay) "UTC" else TimeZone.getDefault().id
        put(CalendarContract.Events.EVENT_TIMEZONE, tz)
        put(CalendarContract.Events.EVENT_LOCATION, location ?: "")
        put(CalendarContract.Events.DESCRIPTION, desc ?: "")
    }

    /** Returns the new event id, or null on failure. */
    fun insertEvent(
        c: Context, calId: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?
    ): Long? = try {
        val uri = c.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            eventValues(calId, title, begin, end, allDay, location, desc)
        )
        uri?.lastPathSegment?.toLongOrNull()
    } catch (_: Exception) {
        null
    }

    fun updateEvent(
        c: Context, eventId: Long, calId: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?
    ): Boolean = try {
        c.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            eventValues(calId, title, begin, end, allDay, location, desc), null, null
        ) > 0
    } catch (_: Exception) {
        false
    }

    fun deleteEvent(c: Context, eventId: Long): Boolean = try {
        c.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null
        ) > 0
    } catch (_: Exception) {
        false
    }
}
