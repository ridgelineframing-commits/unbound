package construction.ridgeline.unbound

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "UnboundCal"

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
    val color: Int,
    val rrule: String,
    val reminderMinutes: Int // -1 = none
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
            val pattern = "%" + SearchUtil.escapeLike(q) + "%"
            c.contentResolver.query(
                builder.build(), proj,
                "${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'", arrayOf(pattern),
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
        } catch (e: Exception) {
            Log.w(TAG, "searchEvents failed", e)
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
        } catch (e: Exception) {
            Log.w(TAG, "writableCalendars failed", e)
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
            CalendarContract.Events.DISPLAY_COLOR,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DURATION
        )
        try {
            c.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                proj, null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    val allDay = cur.getInt(5) == 1
                    // Recurring events store DTSTART + DURATION (no DTEND); derive an end.
                    val begin = cur.getLong(3)
                    val end = when {
                        !cur.isNull(4) -> cur.getLong(4)
                        else -> begin + DurationUtil.toMillis(cur.getString(10), allDay)
                    }
                    return EventDetail(
                        id = cur.getLong(0),
                        calId = cur.getLong(1),
                        title = cur.getString(2) ?: "",
                        begin = begin,
                        end = end,
                        allDay = allDay,
                        location = cur.getString(6) ?: "",
                        description = cur.getString(7) ?: "",
                        color = cur.getInt(8),
                        rrule = cur.getString(9) ?: "",
                        reminderMinutes = reminderMinutes(c, eventId)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "eventById failed", e)
        }
        return null
    }

    /** Minutes-before of the first reminder on an event, or -1 if none. */
    fun reminderMinutes(c: Context, eventId: Long): Int {
        try {
            c.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null
            )?.use { cur -> if (cur.moveToFirst()) return cur.getInt(0) }
        } catch (e: Exception) {
            Log.w(TAG, "reminderMinutes failed", e)
        }
        return -1
    }


    private fun eventValues(
        calId: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?, rrule: String?, hasReminder: Boolean
    ) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, begin)
        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        // All-day events are stored against UTC midnight per CalendarContract.
        put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
        put(CalendarContract.Events.EVENT_LOCATION, location ?: "")
        put(CalendarContract.Events.DESCRIPTION, desc ?: "")
        put(CalendarContract.Events.HAS_ALARM, if (hasReminder) 1 else 0)
        if (rrule.isNullOrEmpty()) {
            put(CalendarContract.Events.DTEND, end)
            putNull(CalendarContract.Events.RRULE)
            putNull(CalendarContract.Events.DURATION)
        } else {
            // A recurring event must carry DTSTART + DURATION, never DTEND.
            putNull(CalendarContract.Events.DTEND)
            put(CalendarContract.Events.RRULE, rrule)
            val ms = (end - begin).coerceAtLeast(if (allDay) 86_400_000L else 300_000L)
            put(CalendarContract.Events.DURATION, DurationUtil.format(ms, allDay))
        }
    }

    private fun reminderInsert(): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)

    /**
     * Insert an event and its reminder as one atomic batch, so we never end up with
     * an event whose reminder silently failed to save. Returns the new id or null.
     */
    fun insertEvent(
        c: Context, calId: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?, rrule: String?, reminderMinutes: Int
    ): Long? = try {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(eventValues(calId, title, begin, end, allDay, location, desc, rrule, reminderMinutes >= 0))
                .build()
        )
        if (reminderMinutes >= 0) {
            ops.add(
                reminderInsert()
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0) // event from op 0
                    .withValue(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    .build()
            )
        }
        val results = c.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "insertEvent failed", e)
        null
    }

    /** Update an event and rewrite its single reminder atomically. */
    fun updateEvent(
        c: Context, eventId: Long, calId: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?, rrule: String?, reminderMinutes: Int
    ): Boolean = try {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
                .withValues(eventValues(calId, title, begin, end, allDay, location, desc, rrule, reminderMinutes >= 0))
                .build()
        )
        ops.add(
            ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                .withSelection("${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
                .build()
        )
        if (reminderMinutes >= 0) {
            ops.add(
                reminderInsert()
                    .withValue(CalendarContract.Reminders.EVENT_ID, eventId)
                    .withValue(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    .build()
            )
        }
        val results = c.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        (results.getOrNull(0)?.count ?: 0) > 0
    } catch (e: Exception) {
        Log.w(TAG, "updateEvent failed", e)
        false
    }

    fun deleteEvent(c: Context, eventId: Long): Boolean = try {
        c.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null
        ) > 0
    } catch (e: Exception) {
        Log.w(TAG, "deleteEvent failed", e)
        false
    }

    // ---- recurring-series edits (this / this-and-future / all) ---------------

    /** Cancel a single occurrence of a recurring event (a "this event" delete). */
    fun cancelInstance(c: Context, eventId: Long, instanceStartMs: Long): Boolean = try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId)
        val cv = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceStartMs)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
        }
        c.contentResolver.insert(uri, cv) != null
    } catch (e: Exception) {
        Log.w(TAG, "cancelInstance failed", e)
        false
    }

    /** Override a single occurrence with new values (a "this event" edit). */
    fun updateInstance(
        c: Context, eventId: Long, instanceStartMs: Long, title: String, begin: Long, end: Long,
        allDay: Boolean, location: String?, desc: String?
    ): Boolean = try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId)
        val cv = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceStartMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location ?: "")
            put(CalendarContract.Events.DESCRIPTION, desc ?: "")
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }
        c.contentResolver.insert(uri, cv) != null
    } catch (e: Exception) {
        Log.w(TAG, "updateInstance failed", e)
        false
    }

    /**
     * End the recurring series just before [instanceStartMs] by adding an UNTIL to
     * its RRULE (used by both "this and future" edit — paired with a fresh series —
     * and "this and future" delete). Returns false if the event isn't recurring.
     */
    fun capSeriesBefore(c: Context, eventId: Long, instanceStartMs: Long): Boolean = try {
        val detail = eventById(c, eventId)
        if (detail == null || detail.rrule.isEmpty()) {
            false
        } else {
            val kept = detail.rrule.split(";")
                .filter { it.isNotEmpty() && !it.startsWith("UNTIL=") && !it.startsWith("COUNT=") }
            val fmt = SimpleDateFormat(if (detail.allDay) "yyyyMMdd" else "yyyyMMdd'T'HHmmss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val until = fmt.format(Date(instanceStartMs - 1000)) // strictly before this occurrence
            val newRule = (kept + "UNTIL=$until").joinToString(";")
            val cv = ContentValues().apply { put(CalendarContract.Events.RRULE, newRule) }
            c.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), cv, null, null
            ) > 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "capSeriesBefore failed", e)
        false
    }
}
