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
    }    @Test fun `folding retains direct choices and the selected correction`() {
        val readings=(0..19).map { "reading$it" }
        val hints=readings.associateWith { com.wasimaster.wmkeyboard.ime.ConversionReadingHint(0.1f,0.5f,true,it=="reading19") }
        val folded=visibleReadingChoices(listOf("direct")+readings,hints,false)
        assertEquals(10,folded.size)
        assertTrue("direct" in folded)
        assertTrue("reading19" in folded)
        assertFalse("reading10" in folded)
        assertEquals(listOf("direct")+readings,visibleReadingChoices(listOf("direct")+readings,hints,true))
    }

}
