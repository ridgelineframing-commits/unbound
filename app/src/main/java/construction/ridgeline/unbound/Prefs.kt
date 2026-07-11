package construction.ridgeline.unbound

import android.content.Context

object Prefs {
    private const val FILE = "unbound_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun weeks(c: Context, id: Int): Int = sp(c).getInt("weeks_$id", 2).coerceIn(1, 4)
    fun setWeeks(c: Context, id: Int, w: Int) = sp(c).edit().putInt("weeks_$id", w.coerceIn(1, 4)).apply()

    fun widthPx(c: Context, id: Int): Int = sp(c).getInt("w_$id", 1000).coerceIn(240, 1400)
    fun setWidthPx(c: Context, id: Int, px: Int) = sp(c).edit().putInt("w_$id", px.coerceIn(240, 1400)).apply()

    // Global calendar filter: null == show all
    fun cals(c: Context): Set<String>? = sp(c).getStringSet("cals_global", null)?.let { HashSet(it) }
    fun setCals(c: Context, s: Set<String>) = sp(c).edit().putStringSet("cals_global", HashSet(s)).apply()
}
