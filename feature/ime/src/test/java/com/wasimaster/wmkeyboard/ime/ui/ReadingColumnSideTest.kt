package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import org.junit.Assert.*
import org.junit.Test

class ReadingColumnSideTest {
    @Test fun `column follows the active hand and normal mode retains the left side`() {
        assertFalse(readingColumnOnRight(KeyboardSettings(),false))
        assertFalse(readingColumnOnRight(KeyboardSettings(oneHandedMode=OneHandedMode.LEFT),false))
        assertTrue(readingColumnOnRight(KeyboardSettings(oneHandedMode=OneHandedMode.RIGHT),false))
    }
    @Test fun `portrait only mode does not move the column in landscape`() {
        val settings=KeyboardSettings(oneHandedMode=OneHandedMode.RIGHT)
        assertFalse(readingColumnOnRight(settings.copy(oneHanded=settings.oneHanded.copy(portraitOnly=true)),true))
        assertTrue(readingColumnOnRight(settings.copy(oneHanded=settings.oneHanded.copy(portraitOnly=false)),true))
    }
}
