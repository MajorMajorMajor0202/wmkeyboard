package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinGlide
import org.junit.Assert.*
import org.junit.Test

class PinyinGlideSessionTest {
    @Test fun `several confirmations undo in order and restore pending stroke`() {
        val session=PinyinGlideSession()
        val a=listOf(GesturePoint(0f,0f,1),GesturePoint(1f,0f,2))
        val b=listOf(GesturePoint(2f,1f,3),GesturePoint(3f,1f,4))
        session.select(PinyinGlideChoice("you","yz"),a)
        session.select(PinyinGlideChoice("guan","gr"),b)
        assertEquals(listOf("you","guan"),session.choices.map { it.reading })
        assertEquals(b,session.undo())
        assertEquals(listOf("you"),session.choices.map { it.reading })
        assertEquals(a,session.undo());assertFalse(session.canUndo);assertNull(session.undo())
    }
    @Test fun `a local circle repeats where jitter and backtracking do not`() {
        val keys=listOf(DoublePinyinGlide.Key("lm",0.0,0.0),DoublePinyinGlide.Key("jk",3.0,0.0))
        val jitter=listOf(DoublePinyinGlide.Point(0.0,0.0,0),DoublePinyinGlide.Point(0.1,0.1,120),DoublePinyinGlide.Point(0.0,0.0,400))
        assertEquals(listOf(0),PinyinGlideSession.visits(jitter,keys))
        val backtrack = listOf(0.0, 1.0, 0.0).map { DoublePinyinGlide.Point(it, 0.0) }
        assertEquals(listOf(0), PinyinGlideSession.visits(backtrack, keys))
        val circle = List(25) { i ->
            val angle = i * 2 * Math.PI / 24
            DoublePinyinGlide.Point(0.3 * kotlin.math.cos(angle), 0.3 * kotlin.math.sin(angle))
        }
        assertEquals(listOf(0, 0), PinyinGlideSession.visits(circle, keys))
    }

    @Test fun `a full combination still undoes one syllable and only that syllables stroke`() {
        val session=PinyinGlideSession()
        val first=listOf(GesturePoint(0f,0f,10))
        val second=listOf(GesturePoint(2f,0f,20))
        val choice=PinyinGlideChoice("youguan","yzgr",listOf(
            PinyinGlideChoice("you","yz",stroke=first),PinyinGlideChoice("guan","gr",stroke=second)))
        session.select(choice,first+second)
        assertEquals(second,session.undo())
        assertEquals(listOf("you"),session.choices.map { it.reading })
        assertEquals(first,session.undo())
    }
}
