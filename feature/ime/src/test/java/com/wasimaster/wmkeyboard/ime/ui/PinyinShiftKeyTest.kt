package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.input.composer.CompactPinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.hasHoldAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinShiftKeyTest {
    @Test fun `number row removes both compact digits even with digit holds requested`() {
        val state = KeyboardUiState(composer = CompactPinyinComposer)
        val settings = state.settings.copy(numberRow = true, layoutBehavior = state.settings.layoutBehavior.copy(
            topRowHolds = com.wasimaster.wmkeyboard.core.settings.TopRowHolds.DIGITS))
        val shown = currentLayout(state.copy(settings = settings, layouts = state.layouts.copy(letters = grid)))
        assertTrue(shown.rows.first().all { key -> key.hint.orEmpty().none { it.isDigit() } })
        assertTrue(shown.rows.first().all { key -> key.longPress.none { it.length == 1 && it[0].isDigit() } })
        assertEquals(listOf("12", "34", "56", "78", "90"), grid.rows.first().map { it.hint })
    }

    private val grid = BuiltInLayouts.COMPACT_PINYIN.compile(LayoutLayer.LETTERS)

    @Test fun `idle Shift and composing separator have exclusive holds and identical positions`() {
        val state = KeyboardUiState(composer = CompactPinyinComposer)
        val idle = grid.withPinyinShift(state)
        val composing = grid.withPinyinShift(state.copy(composingPreview = "ni"))
        val shift = idle.rows[2][0]
        val split = composing.rows[2][0]
        assertEquals(KeyAction.Shift, shift.action)
        assertFalse(shift.hasHoldAction())
        assertTrue(shift.actionAlternates.isEmpty())
        assertEquals(KeyAction.Text, split.action)
        assertEquals("'", split.output)
        assertTrue(split.hasHoldAction())
        assertEquals(KeyAction.HanScript, split.holdAction!!.action)
        assertTrue(split.longPress.isEmpty())
        assertTrue(split.actionAlternates.isEmpty())
        assertEquals(shift.width, split.width)
        assertEquals(idle, grid.withPinyinShift(state.copy(composingPreview = "")))
    }

    @Test fun `ordinary Pinyin separator slot becomes Shift only while idle`() {
        val rows = grid.rows.mapIndexed { i, row -> row.mapIndexed { j, key ->
            if (i == 2 && j == 0) key.copy(label = "'词", output = "'", action = KeyAction.Text,
                actionAlternates = listOf(KeyAlternate(KeyAction.HanScript))) else key
        } }
        val pinyin = grid.copy(rows = rows)
        val state = KeyboardUiState(composer = PinyinComposer)
        assertEquals(KeyAction.Shift, pinyin.withPinyinShift(state).rows[2][0].action)
        assertEquals(KeyAction.HanScript, pinyin.withPinyinShift(state.copy(composingPreview = "ni")).rows[2][0].holdAction!!.action)
    }

    @Test fun `English Compact remains unchanged`() {
        val english = BuiltInLayouts.COMPACT.compile(LayoutLayer.LETTERS)
        assertEquals(english, english.withPinyinShift(KeyboardUiState(composingPreview = "hello")))
    }
}
