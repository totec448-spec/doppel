package de.totec.doppel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9: the picker used to open on an empty list and could not reach past its first page.
 *
 * Two policies have to agree for that to stay fixed. Paging counts positions in the *filtered*
 * list, not the catalog, and a new query restarts at page one — otherwise a search typed while
 * scrolled deep would present its matches from the middle, which reads as "the list is broken"
 * exactly like the original bug did.
 */
class ModelPickerPresentationTest {
    /** Stand-in for the catalog as `UiSettingsMapper` hands it over: already newest first. */
    private val catalog =
        (1..57).map { "vendor/model-$it" to "Model $it · 2026-0${(it % 9) + 1}-01" }

    @Test
    fun `the list opens on a full first page without any search`() {
        val matches = ModelPickerPresentation.matches(catalog, "")
        val visible = ModelPickerPresentation.firstVisibleCount(matches.size)

        assertEquals(57, matches.size)
        assertEquals(20, visible)
        assertEquals(20, ModelPickerPresentation.page(matches, visible, "").size)
    }

    /** Never load the whole catalog at once: the untouched tail is the point of the paging. */
    @Test
    fun `the catalog order is preserved and never fully materialized`() {
        val matches = ModelPickerPresentation.matches(catalog, "")
        val page = ModelPickerPresentation.page(matches, ModelPickerPresentation.firstVisibleCount(matches.size), "")

        assertEquals(catalog.take(20).map { it.first }, page.map { it.value })
        assertTrue(page.size < catalog.size)
    }

    @Test
    fun `scrolling to the end of a page reveals exactly twenty more`() {
        var visible = ModelPickerPresentation.firstVisibleCount(catalog.size)

        assertTrue(ModelPickerPresentation.shouldLoadMore(19, visible, catalog.size))
        visible = ModelPickerPresentation.nextVisibleCount(visible, catalog.size)
        assertEquals(40, visible)

        assertTrue(ModelPickerPresentation.shouldLoadMore(39, visible, catalog.size))
        visible = ModelPickerPresentation.nextVisibleCount(visible, catalog.size)
        assertEquals(57, visible)
    }

    @Test
    fun `paging stops at the end of the list`() {
        val visible = ModelPickerPresentation.nextVisibleCount(50, catalog.size)

        assertEquals(57, visible)
        assertFalse(ModelPickerPresentation.shouldLoadMore(56, visible, catalog.size))
        assertEquals(0, ModelPickerPresentation.remaining(visible, catalog.size))
    }

    /** Mid-page scrolling must not page ahead: that is how a list loads itself entirely by accident. */
    @Test
    fun `scrolling inside a page loads nothing`() {
        assertFalse(ModelPickerPresentation.shouldLoadMore(12, 20, catalog.size))
        assertFalse(ModelPickerPresentation.shouldLoadMore(0, 20, catalog.size))
    }

    @Test
    fun `search matches the slug and the label alike`() {
        val options =
            listOf(
                "google/gemini-3.1-flash" to "Gemini 3.1 Flash",
                "deepseek/deepseek-v4-flash" to "DeepSeek V4 Flash",
                "x-ai/grok-5" to "Grok 5",
            )

        assertEquals(
            listOf("google/gemini-3.1-flash"),
            ModelPickerPresentation.matches(options, "gemini").map { it.first },
        )
        assertEquals(
            listOf("google/gemini-3.1-flash", "deepseek/deepseek-v4-flash"),
            ModelPickerPresentation.matches(options, "FLASH").map { it.first },
        )
        assertEquals(
            listOf("x-ai/grok-5"),
            ModelPickerPresentation.matches(options, "  Grok  ").map { it.first },
        )
        assertEquals(emptyList<String>(), ModelPickerPresentation.matches(options, "claude").map { it.first })
    }

    /**
     * Search and paging together: a query that still matches more than one page must page through
     * its own results, not through the catalog.
     */
    @Test
    fun `a search result pages through its own matches`() {
        val matches = ModelPickerPresentation.matches(catalog, "Model 1")
        // model-1 and model-10..model-19
        assertEquals(11, matches.size)

        val visible = ModelPickerPresentation.firstVisibleCount(matches.size)
        assertEquals(11, visible)
        assertFalse(ModelPickerPresentation.shouldLoadMore(10, visible, matches.size))
    }

    @Test
    fun `a query resets paging to the first page`() {
        val all = ModelPickerPresentation.matches(catalog, "")
        val deep = ModelPickerPresentation.nextVisibleCount(ModelPickerPresentation.firstVisibleCount(all.size), all.size)
        assertEquals(40, deep)

        // Typing a query rebuilds the visible count from the new result set, never from `deep`.
        val narrowed = ModelPickerPresentation.matches(catalog, "vendor/model-2")
        assertEquals(11, narrowed.size)
        assertEquals(11, ModelPickerPresentation.firstVisibleCount(narrowed.size))
        assertEquals(
            listOf("vendor/model-2", "vendor/model-20", "vendor/model-21"),
            ModelPickerPresentation.page(narrowed, 3, "").map { it.value },
        )

        // And clearing it drops back to one page instead of resuming at the old depth.
        assertEquals(20, ModelPickerPresentation.firstVisibleCount(ModelPickerPresentation.matches(catalog, "").size))
    }

    @Test
    fun `the selected model is marked wherever it appears`() {
        val matches = ModelPickerPresentation.matches(catalog, "")
        val page = ModelPickerPresentation.page(matches, 20, "vendor/model-7")

        assertEquals(listOf("vendor/model-7"), page.filter { it.selected }.map { it.value })
    }

    /** A selection outside the current page simply is not marked — it is not an error state. */
    @Test
    fun `a selection off the current page marks nothing`() {
        val page = ModelPickerPresentation.page(catalog, 20, "vendor/model-55")

        assertTrue(page.none { it.selected })
    }

    @Test
    fun `an empty catalog stays empty instead of paging into nothing`() {
        assertEquals(0, ModelPickerPresentation.firstVisibleCount(0))
        assertEquals(0, ModelPickerPresentation.nextVisibleCount(0, 0))
        assertFalse(ModelPickerPresentation.shouldLoadMore(0, 0, 0))
        assertEquals(emptyList<ModelPickerRow>(), ModelPickerPresentation.page(emptyList(), 20, "x"))
        assertEquals(0, ModelPickerPresentation.remaining(0, 0))
    }

    /** The footer promises a real number, so it must never over-promise the last partial page. */
    @Test
    fun `the footer announces only what is actually left`() {
        assertEquals(20, ModelPickerPresentation.remaining(20, catalog.size))
        assertEquals(17, ModelPickerPresentation.remaining(40, catalog.size))
        assertEquals(0, ModelPickerPresentation.remaining(57, catalog.size))
    }

    /**
     * Walking the whole catalog page by page has to terminate and yield every model exactly once,
     * which is the property the old hard `take` violated.
     */
    @Test
    fun `paging eventually reaches every model exactly once`() {
        val matches = ModelPickerPresentation.matches(catalog, "")
        var visible = ModelPickerPresentation.firstVisibleCount(matches.size)
        var pages = 1
        while (ModelPickerPresentation.shouldLoadMore(visible - 1, visible, matches.size)) {
            visible = ModelPickerPresentation.nextVisibleCount(visible, matches.size)
            pages++
        }

        assertEquals(3, pages)
        assertEquals(catalog.map { it.first }, ModelPickerPresentation.page(matches, visible, "").map { it.value })
    }

    /**
     * The regression this guards: every empty list rendered as "No catalogue match", so a missing
     * API key and a failed provider call both looked like a search that found nothing. An operator
     * had no reason to retry — the screen claimed the catalogue had been consulted.
     */
    @Test
    fun `an empty catalogue is never reported as a failed search`() {
        assertEquals(
            ModelPickerPlaceholder.LOADING,
            ModelPickerPresentation.placeholder(UiCatalogStatus.LOADING, catalogSize = 0, matchCount = 0),
        )
        assertEquals(
            ModelPickerPlaceholder.ERROR,
            ModelPickerPresentation.placeholder(UiCatalogStatus.ERROR, catalogSize = 0, matchCount = 0),
        )
        assertEquals(
            ModelPickerPlaceholder.EMPTY_CATALOG,
            ModelPickerPresentation.placeholder(UiCatalogStatus.READY, catalogSize = 0, matchCount = 0),
        )
    }

    /** Opening the picker requests the catalogue, so idle must not flash an empty result first. */
    @Test
    fun `an untouched picker reads as loading rather than empty`() {
        assertEquals(
            ModelPickerPlaceholder.LOADING,
            ModelPickerPresentation.placeholder(UiCatalogStatus.IDLE, catalogSize = 0, matchCount = 0),
        )
    }

    /**
     * Once a catalogue exists the query owns the empty list, whatever the last fetch status was —
     * a stale error must not relabel a narrow search as a provider failure.
     */
    @Test
    fun `a narrow search over a real catalogue stays a search result`() {
        assertEquals(
            ModelPickerPlaceholder.NO_MATCH,
            ModelPickerPresentation.placeholder(UiCatalogStatus.READY, catalog.size, matchCount = 0),
        )
        assertEquals(
            ModelPickerPlaceholder.NO_MATCH,
            ModelPickerPresentation.placeholder(UiCatalogStatus.ERROR, catalog.size, matchCount = 0),
        )
    }

    /** Rows on screen outrank every status: nothing may cover a list the operator can use. */
    @Test
    fun `visible rows suppress the placeholder entirely`() {
        assertEquals(
            ModelPickerPlaceholder.NONE,
            ModelPickerPresentation.placeholder(UiCatalogStatus.LOADING, catalog.size, matchCount = 20),
        )
        assertEquals(
            ModelPickerPlaceholder.NONE,
            ModelPickerPresentation.placeholder(UiCatalogStatus.ERROR, catalog.size, matchCount = 1),
        )
    }

}
