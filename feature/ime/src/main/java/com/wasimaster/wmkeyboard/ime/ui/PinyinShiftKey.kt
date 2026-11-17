package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.input.composer.CompactPinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.captureTarget

internal fun KeyboardUiState.pinyinShiftComposing(): Boolean =
    (composer === PinyinComposer || composer === CompactPinyinComposer) &&
        if (captureTarget() != null) captureReading.isNotEmpty() else composingPreview.isNotEmpty() || wordComposing

/** One physical slot, one active hold: Shift idle, syllable separator during conversion. */
internal fun KeyboardLayout.withPinyinShift(state: KeyboardUiState): KeyboardLayout {
    if (state.layoutMode != LayoutMode.LETTERS ||
        (state.composer !== PinyinComposer && state.composer !== CompactPinyinComposer)
    ) return this
    val composing = state.pinyinShiftComposing()
    return copy(rows = rows.map { row -> row.map { key ->
        val separator = key.action == KeyAction.Text && key.output == "'" &&
            key.actionAlternates.any { it.action == KeyAction.HanScript }
        if (key.action != KeyAction.Shift && !separator) key else if (composing) {
            key.copy(
                label = "分词", output = "'", action = KeyAction.Text,
                longPress = emptyList(), actionAlternates = emptyList(),
                holdAction = KeyAlternate(action = KeyAction.HanScript, label = "简/繁"),
                clipboardAction = null, hint = if (state.settings.cjk.traditionalOutput) "简" else "繁",
                icon = null, iconHint = null,
            )
        } else {
            key.copy(
                label = "⇧", output = null, action = KeyAction.Shift,
                longPress = emptyList(), actionAlternates = emptyList(),
                holdAction = null, clipboardAction = null, hint = null, icon = null, iconHint = null,
            )
        }
    } })
}
