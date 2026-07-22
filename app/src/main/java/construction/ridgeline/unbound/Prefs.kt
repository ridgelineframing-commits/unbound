package construction.ridgeline.unbound

import android.content.Context
import android.content.res.Configuration

object Prefs {
    private const val FILE = "unbound_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // -------- per-widget --------
    fun weeks(c: Context, id: Int): Int = sp(c).getInt("weeks_$id", 2).coerceIn(1, 4)
    fun setWeeks(c: Context, id: Int, w: Int) = sp(c).edit().putInt("weeks_$id", w.coerceIn(1, 4)).apply()

    /** 0 = weeks grid, 1 = agenda (today + next 29 days), 2 = month grid + agenda */
    fun mode(c: Context, id: Int): Int = sp(c).getInt("mode_$id", 0).coerceIn(0, 2)
    fun setMode(c: Context, id: Int, m: Int) = sp(c).edit().putInt("mode_$id", m.coerceIn(0, 2)).apply()

    fun widthPx(c: Context, id: Int): Int = sp(c).getInt("w_$id", 1000).coerceIn(240, 1600)
    fun setWidthPx(c: Context, id: Int, px: Int) = sp(c).edit().putInt("w_$id", px.coerceIn(240, 1600)).apply()

    // available height (px) of the scroll area, so weeks can stretch to fill it
    fun listHeightPx(c: Context, id: Int): Int = sp(c).getInt("h_$id", 0).coerceIn(0, 4000)
    fun setListHeightPx(c: Context, id: Int, px: Int) = sp(c).edit().putInt("h_$id", px.coerceIn(0, 4000)).apply()

    // -------- appearance (global) --------
    /** 0 = follow system, 1 = light, 2 = dark */
    fun theme(c: Context): Int = sp(c).getInt("theme", 0).coerceIn(0, 2)
    fun setTheme(c: Context, v: Int) = sp(c).edit().putInt("theme", v.coerceIn(0, 2)).apply()

    /** Background opacity, 20..100 (%) */
    fun opacity(c: Context): Int = sp(c).getInt("opacity", 100).coerceIn(20, 100)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v.coerceIn(20, 100)).apply()

    fun showHeader(c: Context): Boolean = sp(c).getBoolean("header", true)
    fun setShowHeader(c: Context, v: Boolean) = sp(c).edit().putBoolean("header", v).apply()

    /** 0 = small, 1 = medium, 2 = large */
    fun textSize(c: Context): Int = sp(c).getInt("textsize", 1).coerceIn(0, 2)
    fun setTextSize(c: Context, v: Int) = sp(c).edit().putInt("textsize", v.coerceIn(0, 2)).apply()

    fun textScale(c: Context): Float = when (textSize(c)) {
        0 -> 0.88f
        2 -> 1.18f
        else -> 1f
    }

    /** true = week starts Monday, false = Sunday */
    fun weekStartsMonday(c: Context): Boolean = sp(c).getBoolean("monday", false)
    fun setWeekStartsMonday(c: Context, v: Boolean) = sp(c).edit().putBoolean("monday", v).apply()

    /** Draw a strike through days that are already over */
    fun strikePast(c: Context): Boolean = sp(c).getBoolean("strike", true)
    fun setStrikePast(c: Context, v: Boolean) = sp(c).edit().putBoolean("strike", v).apply()

    fun resolveDark(c: Context): Boolean = when (theme(c)) {
        1 -> false
        2 -> true
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    // -------- calendar filter (global) --------
    // Stored as an EXCLUSION list so newly synced calendars show up automatically.
    // (The old allow-list style silently hid any calendar synced after setup.)
    fun hiddenCals(c: Context): Set<String> =
        sp(c).getStringSet("cals_hidden", null)?.let { HashSet(it) } ?: emptySet()

    fun setHiddenCals(c: Context, s: Set<String>) =
        sp(c).edit().putStringSet("cals_hidden", HashSet(s)).apply()
}
