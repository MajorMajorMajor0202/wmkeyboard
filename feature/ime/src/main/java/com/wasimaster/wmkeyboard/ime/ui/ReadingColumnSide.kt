package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.oneHandedModeFor

internal fun readingColumnOnRight(settings: KeyboardSettings, landscape: Boolean): Boolean =
    settings.oneHandedModeFor(landscape) == OneHandedMode.RIGHT
