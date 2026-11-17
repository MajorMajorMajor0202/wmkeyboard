package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter

/** Relocation is ignored until a key is deliberately held or used as a turning point. */
internal class PinyinGlideRestart {
    private var cell: Rect? = null
    private var latest: GesturePoint? = null
    private var stillAt = Offset.Zero
    private var stillSince = 0L
    private var selectionCell: Rect? = null
    private var selectionOrigin: Offset? = null
    private val approach = ArrayDeque<GesturePoint>()

    val deadline: Long? get() = latest?.let { stillSince + 45L }

    fun clear() {
        cell = null
        latest = null
        selectionCell = null
        selectionOrigin = null
        approach.clear()
    }

    /** Waiting over a selected candidate must never create another syllable. */
    fun afterSelection(letterCell: Rect?, finger: Offset? = null) {
        clear()
        selectionCell = letterCell
        selectionOrigin = finger
    }

    fun settle(now: Long): GesturePoint? = latest?.takeIf { now >= stillSince + 45L }?.copy(t = now)

    fun sample(point: GesturePoint, letterCell: Rect?, width: Float): Pair<GesturePoint, GesturePoint>? {
        if (width <= 0f || !point.x.isFinite() || !point.y.isFinite()) {
            clear()
            return null
        }
        val at = Offset(point.x, point.y)
        if (selectionCell?.contains(at) == true) {
            val origin = selectionOrigin ?: at.also { selectionOrigin = it }
            if ((at - origin).getDistance() < width * 0.18f) {
                approach.addLast(point)
                while (approach.size > 64 || (approach.size > 1 && point.t - approach.first().t > 250L)) approach.removeFirst()
                return null
            }
            // A deliberate relocation can choose another point in the same
            // key. Merely waiting or jittering over the candidate cannot.
        }
        selectionCell = null
        selectionOrigin = null
        if (letterCell == null) {
            cell = null
            latest = null
            approach.clear()
            return null
        }
        latest = point
        if (cell != letterCell || (at - stillAt).getDistance() > width * 0.10f) {
            stillAt = at
            stillSince = point.t
        }
        cell = letterCell
        approach.addLast(point)
        while (approach.size > 64 || (approach.size > 1 && point.t - approach.first().t > 250L)) approach.removeFirst()
        val reach = width * 0.11f
        // Accumulate travel across reports, so a high refresh rate does not
        // shrink the incoming vector to a few pixels and lose every turn.
        val history = approach.toList()
        for (i in history.lastIndex - 1 downTo 1) {
            val bend = history[i]
            val bendAt = Offset(bend.x, bend.y)
            if (!letterCell.contains(bendAt)) continue
            val outgoing = at - bendAt
            if (outgoing.getDistance() < reach) continue
            val lead = history.subList(0, i).lastOrNull {
                (bendAt - Offset(it.x, it.y)).getDistance() >= reach
            } ?: continue
            val incoming = bendAt - Offset(lead.x, lead.y)
            val cosine = (incoming.x * outgoing.x + incoming.y * outgoing.y) /
                (incoming.getDistance() * outgoing.getDistance())
            if (cosine < 0.35f) return bend to point
        }
        return null
    }
}

/** A visible key owns its entire cell, including wide corners near a staggered row. */
internal fun pinyinKeyForCell(cell: Rect?, keys: List<KeyCenter>): KeyCenter? = cell?.let { rect ->
    keys.minByOrNull { kotlin.math.hypot(it.x-rect.center.x,it.y-rect.center.y) }
}
