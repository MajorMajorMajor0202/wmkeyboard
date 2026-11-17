package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedProfile
import com.wasimaster.wmkeyboard.core.settings.OneHandedSettings
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.bounds
import com.wasimaster.wmkeyboard.ime.SizingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneHandedResizeTest {
    private val entry = ResizeValues(48, 40, 8, sidePadLeft = 0.22f, sidePadRight = 0f)

    @Test fun `match normal height is exact after rounding and retains width and side`() {
        for (scale in listOf(60, 67, 83, 100, 131, 150)) {
            val profile = OneHandedProfile(widthPercent = 73, heightScale = scale, positionPercent = 32, bottomPaddingDp = 55)
            val action = oneHandedMatchNormalHeight(ScreenVariant.PORTRAIT, entry, null, profile)
            assertEquals(profile.copy(heightScale = 100, bottomPaddingDp = null), action.oneHandedProfile)
            assertNull(action.keyHeightDp)
            assertNull(action.numberRowHeightDp)
            assertNull(action.bottomPaddingDp)
        }
        val moved = entry.copy(sidePadLeft = 0.1f, sidePadRight = 0.15f)
        val action = oneHandedMatchNormalHeight(ScreenVariant.LANDSCAPE, entry, moved, OneHandedProfile(heightScale = 67))
        assertEquals(75, action.oneHandedProfile!!.widthPercent)
        assertEquals(40, action.oneHandedProfile!!.positionPercent)
        assertEquals(100, action.oneHandedProfile!!.heightScale)
    }

    @Test fun `one handed bounds can reach both edges and the middle`() {
        val profile = OneHandedProfile(widthPercent = 80)
        assertEquals(0f, profile.bounds(OneHandedMode.LEFT).left, 0.0001f)
        assertEquals(0.2f, profile.bounds(OneHandedMode.RIGHT).left, 0.0001f)
        assertEquals(0.1f, profile.copy(positionPercent = 50).bounds(OneHandedMode.RIGHT).left, 0.0001f)
        assertTrue(OneHandedProfile().widthPercent > 78)
    }

    @Test fun `dragging the centre moves horizontally without changing width`() {
        val moved = resizeOneHandedMovedBy(entry, -0.1f)
        assertEquals(0.12f, moved.sidePadLeft, 0.0001f)
        assertEquals(0.10f, moved.sidePadRight, 0.0001f)
        assertEquals(0f, resizeOneHandedMovedBy(entry, -5f).sidePadLeft, 0.0001f)
        assertEquals(0f, resizeOneHandedMovedBy(entry, 5f).sidePadRight, 0.0001f)
    }

    @Test fun `edge handles expand to 95 percent and stop at the minimum width`() {
        assertEquals(0.05f, resizeLeftPaddedBy(entry, -1f, true).sidePadLeft, 0.0001f)
        assertEquals(0.70f, resizeLeftPaddedBy(entry, 1f, true).sidePadLeft, 0.0001f)
        val moved = resizeOneHandedMovedBy(entry, -0.12f)
        assertEquals(0.70f - moved.sidePadLeft, resizeRightPaddedBy(moved, -1f, true).sidePadRight, 0.0001f)
    }

    @Test fun `commit writes the one handed profile without touching normal geometry`() {
        val profile = OneHandedProfile(widthPercent = 78, heightScale = 80)
        val result = entry.copy(keyHeightDp = 60, numberRowHeightDp = 50, bottomPaddingDp = 25,
            sidePadLeft = 0.10f, sidePadRight = 0.05f)
        val action = oneHandedResizeCommit(ScreenVariant.PORTRAIT, entry, result, profile) as SizingAction.ResizeCommit
        assertNull(action.keyHeightDp)
        assertNull(action.numberRowHeightDp)
        assertNull(action.sidePadLeftScale)
        assertNull(action.sidePadRightScale)
        assertNull(action.bottomPaddingDp)
        assertEquals(85, action.oneHandedProfile!!.widthPercent)
        assertEquals(100, action.oneHandedProfile!!.heightScale)
        assertEquals(25, action.oneHandedProfile!!.bottomPaddingDp)
        assertEquals(SizingAction.ResizeCancel, oneHandedResizeCommit(ScreenVariant.PORTRAIT, entry, null, profile))
    }

    @Test fun `preview preserves mode and does not leak geometry into the other orientation`() {
        val landscape = OneHandedProfile(widthPercent = 55, heightScale = 70)
        val settings = KeyboardSettings(oneHandedMode = OneHandedMode.RIGHT,
            oneHanded = OneHandedSettings(landscape = landscape))
        val shown = settings.withResizePreview(entry, 1f)
        assertEquals(OneHandedMode.RIGHT, shown.oneHandedMode)
        assertEquals(78, shown.oneHanded.portrait.widthPercent)
        assertEquals(landscape, shown.oneHanded.landscape)
        assertEquals(settings.layoutBehavior, shown.layoutBehavior)
    }

    @Test fun `one handed height handles use profile limits without a save time jump`() {
        val keyRange = oneHandedHeightRange(48, 100)
        val numberRange = oneHandedHeightRange(40, 100)
        val grown = resizeScaledHeights(entry, 3f, keyRange, numberRange)
        assertEquals(72, grown.keyHeightDp)
        val action = oneHandedResizeCommit(ScreenVariant.PORTRAIT, entry, grown,
            OneHandedProfile(heightScale = 100)) as SizingAction.ResizeCommit
        assertEquals(150, action.oneHandedProfile!!.heightScale)
        assertEquals(72, 48 * action.oneHandedProfile!!.heightScale / 100)
        val smallRange = oneHandedHeightRange(24, 60)
        assertEquals(24, resizeScaledHeights(entry.copy(keyHeightDp = 24), 1f, smallRange, numberRange).keyHeightDp)
    }

    @Test fun `one handed handles do not claim the normal side padding limit`() {
        val landscape = entry.copy(sidePadLeft = 0.45f)
        assertFalse(resizeOneHandedSideAtLimit(landscape, true))
        assertTrue(resizeOneHandedSideAtLimit(entry.copy(sidePadLeft = 0.70f), true))
        assertTrue(resizeOneHandedSideAtLimit(entry.copy(sidePadLeft = 0.05f), true))
    }
}
