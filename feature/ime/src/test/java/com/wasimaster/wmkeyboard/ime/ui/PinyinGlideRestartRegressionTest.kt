package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import org.junit.Assert.*
import org.junit.Test

class PinyinGlideRestartRegressionTest {
    private val selected=Rect(100f,100f,200f,160f)
    private val next=Rect(200f,100f,300f,160f)

    @Test fun deliberateRelocationCanRestartInsideTheCandidateKey() {
        val restart=PinyinGlideRestart()
        restart.afterSelection(selected,Offset(150f,130f))
        assertNull(restart.sample(GesturePoint(175f,130f,100),selected,100f))
        assertNull(restart.settle(144))
        assertEquals(175f,restart.settle(145)!!.x,0f)
    }

    @Test fun smallCandidateDriftDoesNotArmAStart() {
        val restart=PinyinGlideRestart()
        restart.afterSelection(selected,Offset(150f,130f))
        for(i in 0..20) {
            assertNull(restart.sample(GesturePoint(150f+(i%4)*3f,130f,i*20L),selected,100f))
            assertNull(restart.deadline)
        }
        assertNull(restart.settle(2000))
    }

    @Test fun selectionLocationNeverArmsAStartEvenAfterWaiting() {
        val restart=PinyinGlideRestart()
        restart.afterSelection(selected)
        assertNull(restart.deadline)
        assertNull(restart.sample(GesturePoint(170f,130f,200),selected,100f))
        assertNull(restart.deadline)
        assertNull(restart.settle(2000))
        assertNull(restart.sample(GesturePoint(210f,130f,210),next,100f))
        assertNull(restart.settle(230))
        val start=restart.settle(260)!!
        assertEquals(210f,start.x,0f)
    }

    @Test fun outsideReturnDoesNotStartAtFirstCrossedKey() {
        val restart=PinyinGlideRestart()
        restart.afterSelection(null)
        restart.sample(GesturePoint(150f,50f,0),null,100f)
        assertNull(restart.sample(GesturePoint(150f,110f,20),selected,100f))
        assertNull(restart.settle(30))
        assertNull(restart.sample(GesturePoint(210f,130f,40),next,100f))
        assertNull(restart.settle(50))
        assertEquals(210f,restart.settle(90)!!.x,0f)
    }

    @Test fun wideKeyCornerUsesVisibleCellInsteadOfClosestRowCentre() {
        val keys=listOf(KeyCenter('a',200f,130f),KeyCenter('b',110f,80f))
        val cell=Rect(100f,100f,300f,160f)
        val finger=Offset(110f,105f)
        assertTrue(cell.contains(finger))
        assertEquals('b'.code,keys.minBy { (Offset(it.x,it.y)-finger).getDistance() }.codePoint)
        assertEquals('a'.code,pinyinKeyForCell(cell,keys)!!.codePoint)
        assertNull(pinyinKeyForCell(null,keys))
    }

    @Test fun straightRelocationAcrossKeysNeverClaimsTheFirstKey() {
        val restart=PinyinGlideRestart()
        restart.afterSelection(selected)
        for(i in 0..30) {
            val x=170f+i*8f
            val cell=when { x<200f -> selected; x<300f -> next; else -> Rect(300f,100f,500f,160f) }
            assertNull(restart.sample(GesturePoint(x,130f,i*4L),cell,100f))
        }
        assertNull(restart.settle(140))
        assertEquals(410f,restart.settle(170)!!.x,0f)
    }

    @Test fun denseTouchReportsPreserveDeliberateTurnOnChosenStartKey() {
        for(step in listOf(2,4,8)) {
            val restart=PinyinGlideRestart()
            restart.afterSelection(selected)
            var t=0L
            for(x in 170..250 step step) {
                assertNull(restart.sample(GesturePoint(x.toFloat(),130f,t),if(x<200) selected else next,100f))
                t+=4L
            }
            var turn: Pair<GesturePoint,GesturePoint>?=null
            for(y in 130 downTo 106 step step) {
                turn=turn ?: restart.sample(GesturePoint(250f,y.toFloat(),t),next,100f)
                t+=4L
            }
            assertNotNull("Lost turn with $step pixel reports",turn)
            assertTrue(next.contains(Offset(turn!!.first.x,turn.first.y)))
        }
    }

    @Test fun chosenStartCanReturnToTheFormerCandidateKeyAfterLeavingIt() {
        val restart=PinyinGlideRestart()
        restart.afterSelection(selected)
        restart.sample(GesturePoint(170f,130f,0),selected,100f)
        restart.sample(GesturePoint(210f,130f,10),next,100f)
        restart.sample(GesturePoint(170f,130f,20),selected,100f)
        assertNull(restart.settle(64))
        assertEquals(170f,restart.settle(65)!!.x,0f)
    }

    @Test fun rotatedCandidateFanAvoidsTheFingerAndOverlapAtScreenEdges() {
        for(x in listOf(10f,60f,200f,340f,390f)) for(slots in listOf(listOf(0,1,2,3),listOf(1,2,3),listOf(0,3))) {
            val anchor=Offset(x,100f)
            val sizes=slots.map { 72f to 48f }
            val points=pinyinFanPositions(slots,sizes,anchor,400f,-300f,300f,1f)
            val rects=points.map { Rect(it.x-36f,it.y-24f,it.x+36f,it.y+24f) }
            rects.forEach {
                val dx=maxOf(it.left-anchor.x,anchor.x-it.right,0f)
                val dy=maxOf(it.top-anchor.y,anchor.y-it.bottom,0f)
                assertTrue("Candidate overlaps fingertip: $it",kotlin.math.hypot(dx,dy)>=29f)
            }
            for(i in rects.indices) for(j in 0 until i) {
                assertFalse("Overlapping candidates at $x: $rects",rects[i].overlaps(rects[j]))
            }
        }
    }
}
