package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.oneHandedModeFor

internal fun readingColumnOnRight(settings: KeyboardSettings, landscape: Boolean): Boolean =
    settings.oneHandedModeFor(landscape) == OneHandedMode.RIGHT

/** Keep the first eight corrections concise; an explicit choice stays visible when folded. */
internal fun visibleReadingChoices(
    readings: List<String>, hints: Map<String, com.wasimaster.wmkeyboard.ime.ConversionReadingHint>, expanded: Boolean,
): List<String> {
    if(expanded) return readings
    var corrections=0
    return readings.filter { reading ->
        val hint=hints[reading]
        if(hint?.neighbour!=true) true else { corrections++;corrections<=8 || hint.selected }
    }
}
