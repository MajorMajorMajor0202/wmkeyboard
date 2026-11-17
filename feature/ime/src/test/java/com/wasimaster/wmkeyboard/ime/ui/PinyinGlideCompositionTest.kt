package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.*
import org.junit.Test

class PinyinGlideCompositionTest {
    @Test fun confirmedPrefixIsInsertedOnlyOnceAcrossPreviewsAndLift() {
        val live = PinyinGlideComposition()
        assertEquals("ni", live.update(10, "editor", "before", "ni"))
        assertEquals("", live.update(10, "editor", "beforeni", "ni"))
        assertEquals("hk", live.update(10, "editor", "beforeni", "nihk"))
        assertEquals("", live.update(10, "editor", "beforenihk", "nihk"))
    }

    @Test fun externalEditInterruptsTheRestOfTheStroke() {
        val live = PinyinGlideComposition()
        live.update(10, "editor", "", "ni")
        assertNull(live.update(10, "editor", "changed", "nihk"))
        assertNull(live.update(10, "editor", "ni", "nihk"))
        assertEquals("ni", live.update(20, "editor", "changed", "ni"))
    }

    @Test fun switchedEditorOrLayoutCannotReplayThePrefix() {
        val live = PinyinGlideComposition()
        live.update(10, "editor/layoutA", "", "ni")
        assertNull(live.update(10, "editor/layoutB", "ni", "nihk"))
        assertEquals("ni", live.update(20, "editor/layoutB", "ni", "ni"))
    }

    @Test fun retiredOrOutOfOrderStrokeCannotWriteAgain() {
        val live = PinyinGlideComposition()
        live.update(10, "editor", "", "ni")
        live.reset()
        assertNull(live.update(10, "editor", "ni", "ni"))
        assertEquals("hk", live.update(20, "editor", "ni", "hk"))
        assertNull(live.update(10, "editor", "nihk", "ni"))
    }

    @Test fun insertionAtReadingCaretTracksTheActualBuffer() {
        val live = PinyinGlideComposition()
        assertEquals("ni", live.update(10, "editor", "beforeafter", "ni"))
        live.recordBuffer("beforeniafter")
        assertEquals("hk", live.update(10, "editor", "beforeniafter", "nihk"))
    }
}
