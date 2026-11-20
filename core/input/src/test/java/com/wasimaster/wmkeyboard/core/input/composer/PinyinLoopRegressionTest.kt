package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PinyinLoopRegressionTest {
    private fun circle(radius: Double, clockwise: Boolean = false, reports: Int = 32,
        x: Double = 0.0, y: Double = 0.0): List<DoublePinyinGlide.Point> = (0..reports).map {
        val angle = it * 2 * PI / reports * if (clockwise) -1 else 1
        DoublePinyinGlide.Point(x + radius * cos(angle), y + radius * sin(angle), it * 5L)
    }

    @Test fun `local circles repeat one physical key on all pinyin boards`() {
        for (width in listOf(1.0, 2.0)) for (radius in listOf(0.16, 0.24, 0.38))
            for (clockwise in listOf(false, true)) for (reports in listOf(8, 32, 400)) {
                val keys = listOf(DoublePinyinGlide.Key("lm", 0.0, 0.0, width),
                    DoublePinyinGlide.Key("jk", width, 0.0, width))
                assertEquals("$width $radius $clockwise $reports", listOf(0, 0),
                    DoublePinyinGlide.intentKeys(circle(radius, clockwise, reports), keys))
            }
    }

    @Test fun `jitter and straight backtracking do not qualify as circles`() {
        val keys = listOf(DoublePinyinGlide.Key("a", 0.0, 0.0))
        assertFalse(DoublePinyinGlide.hasLoop(circle(0.04), keys))
        val backtrack = (0..40).map { DoublePinyinGlide.Point(
            if (it <= 20) it * 0.02 else (40 - it) * 0.02, 0.0, it * 5L) }
        assertFalse(DoublePinyinGlide.hasLoop(backtrack, keys))
        assertFalse(DoublePinyinGlide.hasLoop(circle(1.2), keys))
    }

    @Test fun `a circle stays a repeat while travelling to the next key`() {
        val keys = listOf(DoublePinyinGlide.Key("x", 0.0, 0.0), DoublePinyinGlide.Key("n", 2.0, 0.0))
        val path = circle(0.24) + (1..20).map { DoublePinyinGlide.Point(0.24 + it * 0.088, 0.0, 160 + it * 5L) }
        assertEquals(listOf(0, 0, 1), DoublePinyinGlide.intentKeys(path, keys))
    }

    @Test fun `a rounded horizontal return is not a same key circle`() {
        val df = DoublePinyinGlide.Key("df", 0.0, 0.0, 2.0)
        val keys = listOf(df, DoublePinyinGlide.Key("gh", 1.0, 0.0, 2.0))
        val crossing = circle(0.36, x = 0.33).let { it.drop(16) + it.take(17) }
        assertFalse(DoublePinyinGlide.hasLoop(crossing, keys))
        assertEquals(listOf(0, 1, 0), DoublePinyinGlide.intentKeys(crossing, keys))

        val narrowReturn = circle(0.48).map { it.copy(y = it.y * 0.20) }
        assertFalse(DoublePinyinGlide.hasLoop(narrowReturn, listOf(df)))
        assertTrue(DoublePinyinGlide.hasLoop(circle(0.30), keys))
        val table = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
        val dictionary = ConversionDictionary.parse(sequenceOf("dai\t带\t100"))
        fun decode(path: List<DoublePinyinGlide.Point>) =
            DoublePinyinGlide.decode(path, keys, table, setOf("dai"), true, dictionary, CjkNgrams.EMPTY)
        assertFalse(decode(narrowReturn).any { it.reading == "dai" })
        assertEquals("dai", decode(circle(0.30)).firstOrNull()?.reading)
    }

    @Test fun `a pure loop has a finite repeated-key search cost`() {
        val keys = listOf(DoublePinyinGlide.Key("x", 0.0, 0.0), DoublePinyinGlide.Key("n", 1.0, 0.0))
        val search = DoublePinyinGlide.Search(circle(0.24), keys, listOf("xx", "xn"))
        assertTrue(search.cost("xx").isFinite())
        assertTrue(search.paths().any { it.code == "xx" })
    }

    @Test fun `full pinyin reads a physical key twice before dictionary ranking`() {
        val keys = listOf(DoublePinyinGlide.Key("a", 0.0, 0.0))
        val dict = ConversionDictionary.parse(sequenceOf("a\t啊\t100", "aa\t啊啊\t1000"))
        assertTrue(PinyinGlide.loopReadings(circle(0.24), keys, setOf("a"), dict, CjkNgrams.EMPTY).contains("aa"))
    }
}
