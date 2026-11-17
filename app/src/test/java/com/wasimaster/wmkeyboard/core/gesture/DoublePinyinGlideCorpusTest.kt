package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.input.composer.*
import com.wasimaster.wmkeyboard.core.layout.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Uses the shipped 411-syllable grammar and actual layouts, with no whole-phrase fixture rows. */
class DoublePinyinGlideCorpusTest {
    private val inventory = File("src/main/assets/dictionaries/pinyin_syllables.txt").bufferedReader().useLines { PinyinSyllables.parse(it) }
    private val table = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
    private val dict = ConversionDictionary.parse(sequenceOf(
        "you\t有\t923", "yao\t要\t902", "guanxi\t关系\t664", "youyongchi\t游泳池\t501",
        "wo\t我\t1000", "ni\t你\t1000", "xihuan\t喜欢\t850", "mei\t没\t800",
        "nihao\t你好\t850", "shijie\t世界\t700", "zhongguo\t中国\t800", "ren\t人\t900",
    ))
    private fun keys(compact: Boolean): List<DoublePinyinGlide.Key> {
        val rows = (if (compact) BuiltInLayouts.COMPACT_PINYIN else BuiltInLayouts.QWERTY).compile(LayoutLayer.LETTERS).rows
        val width = rows.maxOf { row -> row.sumOf { it.width.toDouble() } }
        return buildList {
            for ((r,row) in rows.withIndex()) {
                var x = (width-row.sumOf { it.width.toDouble() })/2
                for (k in row) {
                    if (k.action==KeyAction.Text && k.letterSet().isNotEmpty()) add(DoublePinyinGlide.Key(k.letterSet(), x+k.width/2, r+0.5))
                    x+=k.width
                }
            }
        }
    }
    private fun path(code:String,keys:List<DoublePinyinGlide.Key>,noise:Double):List<DoublePinyinGlide.Point> = buildList {
        val centers = code.map { c -> keys.first { c in it.letters } }
        for(i in 1 until centers.size) for(j in 0..12) {
            val a=centers[i-1];val b=centers[i];val f=j/12.0
            add(DoublePinyinGlide.Point(a.x+(b.x-a.x)*f+noise,a.y+(b.y-a.y)*f+noise/2))
        }
    }
    @Test fun `unlisted multiword phrases survive the complete shipped syllable grammar`() {
        val codes=DoublePinyin.keysOf(table,inventory)
        for(compact in listOf(true,false)) for(readings in listOf(
            listOf("you","guan","xi"), listOf("you","mei","you"), listOf("wo","xi","huan"),
            listOf("ni","xi","huan"), listOf("ni","hao","shi","jie"), listOf("zhong","guo","ren"),
        )) {
            val keys=keys(compact);val code=readings.joinToString(""){ codes.getValue(it) }
            val result=DoublePinyinGlide.decode(path(code,keys,0.0),keys,table,inventory,compact,dict,CjkNgrams.EMPTY)
            assertEquals("compact=$compact $readings $result",readings.joinToString(""),result.firstOrNull()?.reading)
        }
    }
    @Test fun `a lightly displaced missing phrase remains the leader`() {
        for(compact in listOf(true,false)) {
            val keys=keys(compact)
            val result=DoublePinyinGlide.decode(path("yzgrxi",keys,0.12),keys,table,inventory,compact,dict,CjkNgrams.EMPTY)
            assertEquals(result.toString(),"youguanxi",result.firstOrNull()?.reading)
        }
    }
}
