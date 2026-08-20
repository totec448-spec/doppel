package de.totec.doppel.ui

import de.totec.doppel.app.UiSettingsMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings list is read by icon before it is read by label, so a category without one is a
 * category that looks broken. The icon map is written by hand, which is exactly the kind of list
 * that silently falls behind when a [de.totec.doppel.settings.SettingGroup] is added.
 */
class SettingsGroupIconsTest {
    @Test
    fun everySettingsCategoryHasItsOwnIcon() {
        val missing = UiSettingsMapper.groupLabels.filterNot { it in GROUP_ICONS }
        assertTrue("Settings categories without an icon: $missing", missing.isEmpty())
    }

    @Test
    fun theIconMapCarriesNoLabelThatIsNotACategory() {
        val unknown = GROUP_ICONS.keys - UiSettingsMapper.groupLabels.toSet()
        assertTrue("Icon entries for unknown categories: $unknown", unknown.isEmpty())
    }

    /**
     * Two categories sharing a glyph would defeat the point: the row would still have to be read to
     * tell it from its neighbour.
     */
    @Test
    fun noTwoCategoriesShareAGlyph() {
        assertEquals(GROUP_ICONS.size, GROUP_ICONS.values.toSet().size)
    }

    /**
     * The settings list leads with these five by name. A renamed category would not fail to
     * compile — it would quietly drop out of the lead section and reappear further down, which is
     * the one place nobody would look for it.
     */
    @Test
    fun theFiveOverridableCategoriesAreRealCategories() {
        val unknown = PER_CHAT_GROUPS - UiSettingsMapper.groupLabels.toSet()
        assertTrue("Overridable categories that no longer exist: $unknown", unknown.isEmpty())
    }
}
