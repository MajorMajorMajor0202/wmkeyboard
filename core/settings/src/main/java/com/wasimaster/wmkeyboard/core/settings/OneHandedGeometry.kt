package com.wasimaster.wmkeyboard.core.settings

data class OneHandedBounds(val width: Float, val left: Float, val right: Float)

fun OneHandedSettings.withProfile(landscape: Boolean, profile: OneHandedProfile): OneHandedSettings =
    if (landscape) copy(landscape = profile) else copy(portrait = profile)

/** Absolute screen fractions, shared by the body and its resize outline. */
fun OneHandedProfile.bounds(mode: OneHandedMode): OneHandedBounds {
    val width = widthPercent.coerceIn(SettingsRepository.ONE_HANDED_WIDTH_MIN, SettingsRepository.ONE_HANDED_WIDTH_MAX) / 100f
    val position = (positionPercent ?: if (mode == OneHandedMode.LEFT) 0 else 100).coerceIn(0, 100) / 100f
    val left = (1f - width) * position
    return OneHandedBounds(width, left, (1f - width - left).coerceAtLeast(0f))
}
