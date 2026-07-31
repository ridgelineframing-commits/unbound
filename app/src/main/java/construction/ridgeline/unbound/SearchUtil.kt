package construction.ridgeline.unbound

/**
 * Pure helpers for building safe SQL `LIKE` queries against the calendar provider.
 * `%` and `_` are LIKE wildcards; a user typing them (or `\`) must be escaped so a
 * search for "50%" doesn't match everything. Pair with `ESCAPE '\'` in the query.
 */
object SearchUtil {

    /** Escape `\`, `%`, and `_` so [query] is treated as a literal inside a LIKE pattern. */
    fun escapeLike(query: String): String =
        query.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
