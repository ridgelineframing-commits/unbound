package construction.ridgeline.unbound

/**
 * Pure classification of an RFC 5545 RRULE into the simple set the editor can
 * present, or CUSTOM for anything richer (INTERVAL>1, BYDAY, COUNT, UNTIL, …)
 * so a complex rule is preserved rather than silently downgraded.
 */
object RecurrenceUtil {

    enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }

    /** The rule string for a simple repeat, or null for NONE/CUSTOM. */
    fun ruleFor(r: Repeat): String? = when (r) {
        Repeat.DAILY -> "FREQ=DAILY"
        Repeat.WEEKLY -> "FREQ=WEEKLY"
        Repeat.MONTHLY -> "FREQ=MONTHLY"
        Repeat.YEARLY -> "FREQ=YEARLY"
        else -> null
    }

    fun classify(rrule: String?): Repeat {
        val s = rrule?.trim().orEmpty()
        if (s.isEmpty()) return Repeat.NONE

        val parts = s.split(";")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim().uppercase() to part.substring(eq + 1).trim()
            }
            .toMap()

        val freq = parts["FREQ"]?.uppercase() ?: return Repeat.CUSTOM

        // Anything beyond a plain FREQ (or INTERVAL=1) means we can't round-trip it.
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val extraKeys = parts.keys - setOf("FREQ", "INTERVAL", "WKST")
        if (interval != 1 || extraKeys.isNotEmpty()) return Repeat.CUSTOM

        return when (freq) {
            "DAILY" -> Repeat.DAILY
            "WEEKLY" -> Repeat.WEEKLY
            "MONTHLY" -> Repeat.MONTHLY
            "YEARLY" -> Repeat.YEARLY
            else -> Repeat.CUSTOM
        }
    }
}
