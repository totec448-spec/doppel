package de.totec.doppel.ui

/** One clickable model row, already resolved against the current selection. */
internal data class ModelPickerRow(
    val value: String,
    val label: String,
    val selected: Boolean,
)

/** What to show instead of rows, once there are no rows to show. */
internal enum class ModelPickerPlaceholder {
    /** There are rows; the list speaks for itself. */
    NONE,

    /** The catalogue is being fetched. Not an error yet, so offer no retry. */
    LOADING,

    /** The fetch failed. The reason and a retry belong on screen. */
    ERROR,

    /** The provider answered with nothing compatible — a real answer, not a failure. */
    EMPTY_CATALOG,

    /** A catalogue exists; this query just does not hit any of it. */
    NO_MATCH,
}

/**
 * Search and paging policy for the model list.
 *
 * The catalog arrives pre-sorted newest first from `UiSettingsMapper`, so this never reorders it —
 * filtering and paging both preserve that order, which is what makes "20 newest, then 20 more"
 * mean anything. The two have to agree: paging counts positions in the *filtered* list, and a new
 * query restarts at the first page, otherwise a search opened while scrolled deep would show its
 * matches from the middle.
 *
 * Kept out of the composable so the policy can be exercised without a Compose runtime.
 */
internal object ModelPickerPresentation {
    /** One screenful plus a little, so the first page never looks like the whole catalog. */
    const val PAGE_SIZE = 20

    /** Matches on id and label alike: operators type both "gemini" and "Gemini 3.1 Flash". */
    fun matches(
        options: List<Pair<String, String>>,
        query: String,
    ): List<Pair<String, String>> {
        val needle = query.trim()
        if (needle.isBlank()) return options
        return options.filter {
            it.first.contains(needle, ignoreCase = true) ||
                it.second.contains(needle, ignoreCase = true)
        }
    }

    fun firstVisibleCount(total: Int): Int = PAGE_SIZE.coerceAtMost(total.coerceAtLeast(0))

    fun nextVisibleCount(
        current: Int,
        total: Int,
    ): Int = (current.coerceAtLeast(0) + PAGE_SIZE).coerceAtMost(total.coerceAtLeast(0))

    /**
     * True once the last row the user can see is the last row that exists. Scrolling further has
     * nothing left to reveal, which is exactly when the next page has to be materialized.
     */
    fun shouldLoadMore(
        lastVisibleIndex: Int,
        visibleCount: Int,
        total: Int,
    ): Boolean = visibleCount < total && lastVisibleIndex >= visibleCount - 1

    fun page(
        matches: List<Pair<String, String>>,
        visibleCount: Int,
        selected: String,
    ): List<ModelPickerRow> =
        matches.take(visibleCount.coerceAtLeast(0)).map { (value, label) ->
            // Release dates are useful for sorting, but repeating one in every visible row turned
            // the phone picker into a wall of competing metadata. The slug already identifies the
            // exact revision; the large line is reserved for the human model name.
            ModelPickerRow(
                value = value,
                label = label,
                selected = value == selected,
            )
        }

    /**
     * Separates "no rows because the search is narrow" from "no rows because the catalogue never
     * arrived". Only the catalogue size can tell those apart: a non-empty catalogue means the
     * fetch worked and the query is doing the filtering, whichever status the last fetch left
     * behind. A still-idle picker counts as loading because opening it always requests the
     * catalogue, and a one-frame "nothing here" would otherwise flash before the request starts.
     */
    fun placeholder(
        status: UiCatalogStatus,
        catalogSize: Int,
        matchCount: Int,
    ): ModelPickerPlaceholder =
        when {
            matchCount > 0 -> ModelPickerPlaceholder.NONE
            catalogSize > 0 -> ModelPickerPlaceholder.NO_MATCH
            status == UiCatalogStatus.ERROR -> ModelPickerPlaceholder.ERROR
            status == UiCatalogStatus.READY -> ModelPickerPlaceholder.EMPTY_CATALOG
            else -> ModelPickerPlaceholder.LOADING
        }

    /** Size of the page still to come, or 0 when the list is fully materialized. */
    fun remaining(
        visibleCount: Int,
        total: Int,
    ): Int = (total - visibleCount.coerceAtLeast(0)).coerceAtLeast(0).coerceAtMost(PAGE_SIZE)
}
