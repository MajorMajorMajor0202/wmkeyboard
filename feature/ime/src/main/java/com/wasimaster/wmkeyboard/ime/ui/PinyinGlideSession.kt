package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinGlide
import kotlin.math.hypot

/** Opaque composer buffers preserve the selected physical groups and syllable boundaries. */
data class PinyinGlideChoice(val reading: String,val buffer: String,
    val parts: List<PinyinGlideChoice> = emptyList(),val stroke: List<GesturePoint> = emptyList(),val pairSlot: Int = -1)
data class PinyinGlidePreview(val start: Long,val end: Long,val choices: List<PinyinGlideChoice>,val keys: Int,
    val keyLabels: String = "")

internal class PinyinGlideSession {
    private data class Checkpoint(val choice: PinyinGlideChoice,val points: List<GesturePoint>)
    private val history=mutableListOf<Checkpoint>()
    val choices: List<PinyinGlideChoice> get()=history.map { it.choice }
    val canUndo: Boolean get()=history.isNotEmpty()
    fun select(choice: PinyinGlideChoice,points: List<GesturePoint>) {
        for(part in choice.parts.ifEmpty { listOf(choice) }) history+=Checkpoint(part,part.stroke.ifEmpty { points }.toList())
    }
    fun undo(): List<GesturePoint>? = if(history.isEmpty()) null else history.removeAt(history.lastIndex).points

    companion object {
        /** Core visits with hysteresis. A departure and return can intentionally repeat a key. */
        fun visits(points: List<DoublePinyinGlide.Point>,keys: List<DoublePinyinGlide.Key>): List<Int> =
            DoublePinyinGlide.intentKeys(points,keys)
    }
}
