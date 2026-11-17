package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Keep the whole fan rigid: screen edges rotate the arc rather than stack its buttons. */
internal fun pinyinFanPositions(
    slots: List<Int>, sizes: List<Pair<Float, Float>>, anchor: Offset,
    width: Float, top: Float, bottom: Float, dp: Float,
): List<Offset> {
    if (slots.isEmpty()) return emptyList()
    val order = slots.indices.sortedBy { slots[it] }
    val margin = 6f * dp
    val gap = 8f * dp
    val step = when (slots.size) {
        1 -> 0f
        2 -> 70f
        3 -> 60f
        else -> 50f
    }
    // A chord longer than a button's diagonal keeps neighbours separate at every tilt.
    val diagonal = sizes.maxOf { (w, h) -> hypot(w + gap, h + gap) }
    val radius = if (slots.size == 1) 66f * dp else maxOf(
        76f * dp, diagonal / (2f * sin(Math.toRadians((step / 2f).toDouble())).toFloat()),
    )
    val retry = Rect(anchor.x - 20f * dp, anchor.y + 32f * dp,
        anchor.x + 20f * dp, anchor.y + 64f * dp)
    var best = emptyList<Offset>()
    var bestCost = Float.POSITIVE_INFINITY
    for (extra in listOf(0f, 8f, 16f)) for (tilt in -100..100 step 5) {
        val positions = MutableList(slots.size) { Offset.Zero }
        order.forEachIndexed { rank, index ->
            val angle = Math.toRadians((-90f + tilt + (rank - (slots.size - 1) / 2f) * step).toDouble())
            positions[index] = anchor + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (radius + extra * dp)
        }
        val rects = positions.mapIndexed { index, p ->
            val (w, h) = sizes[index]
            Rect(p.x - w / 2f, p.y - h / 2f, p.x + w / 2f, p.y + h / 2f)
        }
        // Translate only as a whole if a narrow window cannot fit any rotated arc.
        val left = rects.minOf { it.left }
        val right = rects.maxOf { it.right }
        val upper = rects.minOf { it.top }
        val lower = rects.maxOf { it.bottom }
        val shiftX = maxOf(0f, margin - left) - maxOf(0f, right - width + margin)
        val shiftY = maxOf(0f, top + margin - upper) - maxOf(0f, lower - bottom + margin)
        val shift = Offset(shiftX, shiftY)
        var cost = abs(tilt) * 0.15f + extra * 3f + hypot(shiftX, shiftY) / dp * 12f
        rects.forEach { original ->
            val r = original.translate(shift)
            val overflow = maxOf(0f, margin - r.left) + maxOf(0f, r.right - width + margin) +
                maxOf(0f, top + margin - r.top) + maxOf(0f, r.bottom - bottom + margin)
            cost += overflow / dp * 1000f
            if (r.inflate(gap / 2f).overlaps(retry)) cost += 100000f
            // Keep every target outside the fingertip, even in the translated fallback.
            val distance = hypot(maxOf(r.left - anchor.x, anchor.x - r.right, 0f),
                maxOf(r.top - anchor.y, anchor.y - r.bottom, 0f))
            cost += maxOf(0f, 30f - distance / dp) * 1000f
        }
        if (cost < bestCost) {
            bestCost = cost
            best = positions.map { it + shift }
        }
    }
    return best
}
