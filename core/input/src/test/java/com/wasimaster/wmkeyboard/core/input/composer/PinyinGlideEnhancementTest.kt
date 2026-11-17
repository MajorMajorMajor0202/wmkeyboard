package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.layout.*
import org.junit.Assert.*
import org.junit.Test

class PinyinGlideEnhancementTest {
    @Test fun `thirteen key pairing preserves all letters and the original tools`() {
        val rows=BuiltInLayouts.PINYIN_13.layers.getValue("letters").rows
        assertEquals(listOf("qw","er","tu","yi","op","as","df","gh","jk","lm","zx","cv","bn"),
            rows.flatten().mapNotNull { it.letters })
        assertEquals(('a'..'z').toSet(),rows.flatten().flatMap { it.letterSet().toList() }.toSet())
        assertEquals(BuiltInLayouts.COMPACT_PINYIN.layers.getValue("letters").rows.last(),rows.last())
        assertSame(CompactPinyinComposer,BuiltInLayouts.PINYIN_13.resolvedComposer())
    }
    @Test fun `fourteen key style determines the LM proportions`() {
        val normal=BuiltInLayouts.compactPinyinVariant(BuiltInLayouts.COMPACT_PINYIN,false).layers.getValue("letters")
        val compact=BuiltInLayouts.compactPinyinVariant(BuiltInLayouts.COMPACT_PINYIN,true).layers.getValue("letters")
        fun ratio(layer: LayerSpec)=layer.rows.flatten().first { it.output=="l" }.width / layer.rows.flatten().first { it.letters=="qw" }.width
        assertEquals(0.5f,ratio(normal),0.001f)
        assertEquals(1f,ratio(compact),0.001f)
    }
    @Test fun `same LM key produces Xiaohe mian and its reading can be unlocked without deleting taps`() {
        val saved=PinyinSyllables.valid
        val old=CjkDictionaries.pinyin
        val scheme=CjkConfig.doublePinyin
        try {
            PinyinSyllables.valid=setOf("mian")
            CjkDictionaries.pinyin=ConversionDictionary.parse(sequenceOf("mian\t绵\t100"))
            CjkConfig.doublePinyin=DoublePinyinScheme.XIAOHE
            val table=DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
            val result=DoublePinyinGlide.syllables("lm","lm",table,PinyinSyllables.valid,true,CjkDictionaries.pinyin,CjkNgrams.EMPTY).single()
            assertEquals("mian",result.reading)
            val raw=CompactPinyinComposer.bufferKey("m","lm").repeat(2)
            assertEquals(raw,result.raw)
            val locked=DoublePinyinGlide.bufferFor(result,table,PinyinSyllables.valid,true)!!
            assertEquals("绵",CompactPinyinComposer.candidates(locked).first())
            assertEquals(raw,CompactPinyinComposer.undoReading(locked))
            assertEquals(locked+raw,CompactPinyinComposer.undoReading(locked+locked))
            assertNull(CompactPinyinComposer.undoReading(raw))
        } finally { PinyinSyllables.valid=saved; CjkDictionaries.pinyin=old;CjkConfig.doublePinyin=scheme }
    }
    @Test fun `thirteen key full pinyin uses its own physical ambiguity groups`() {
        val groups=BuiltInLayouts.PINYIN_13.layers.getValue("letters").rows.flatten().mapNotNull { it.letters }
        val encoded=CompactPinyin.encode("ming",groups)
        assertTrue(CompactPinyin.graph(encoded,setOf("ming"),false)[0].any { it.reading=="ming" && it.to==encoded.length })
        assertEquals("lm",CompactPinyin.letters(encoded.first()))
    }

    @Test fun `physical key metadata order does not turn ambiguous groups into exact letters`() {
        for((printed,sorted) in listOf("ui" to "iu","zx" to "xz","yi" to "iy")) {
            assertEquals(CompactPinyin.key(printed.take(1),printed),CompactPinyin.key(printed.take(1),sorted))
        }
        val table=DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
        val result=DoublePinyinGlide.syllables("ty","xz",table,setOf("you"),true,
            ConversionDictionary.parse(sequenceOf("you\t有\t100")),CjkNgrams.EMPTY).single()
        assertEquals(CompactPinyin.encode("yz"),result.raw)
    }

    @Test fun `Chinese compact variants have Chinese punctuation while English stays unchanged`() {
        for(spec in listOf(BuiltInLayouts.PINYIN_13,BuiltInLayouts.COMPACT_PINYIN,
            BuiltInLayouts.compactPinyinVariant(BuiltInLayouts.COMPACT_PINYIN,true))) {
            val keys=spec.layers.getValue("letters").rows.flatten()
            assertEquals("，",keys.first { it.role==KeyRole.Comma }.label)
            assertEquals("。",keys.first { it.role==KeyRole.Period }.label)
            assertTrue(keys.first { it.role==KeyRole.Comma }.longPress.containsAll(listOf("！","？","、",",")))
            assertTrue(keys.any { "？" in it.longPress && it.action==KeyAction.Text })
            assertTrue(keys.any { "'" in it.longPress })
        }
        assertEquals(",",BuiltInLayouts.COMPACT.layers.getValue("letters").rows.flatten().first { it.role==KeyRole.Comma }.label)
        val oldLayer=BuiltInLayouts.COMPACT.layers.getValue("letters").copy(fontScale=1.2f,rowHeights=listOf(1.1f))
        val oldOverride=BuiltInLayouts.COMPACT_PINYIN.copy(layers=mapOf("letters" to oldLayer))
        val healed=BuiltInLayouts.compactPinyinVariant(oldOverride,false).layers.getValue("letters")
        assertEquals(1.2f,healed.fontScale!!,0.001f)
        assertEquals(oldLayer.rowHeights,healed.rowHeights)
        assertTrue(healed.rows.flatten().first { it.output=="m" }.longPress.contains("？"))
        assertTrue(healed.rows.flatten().first { it.letters=="df" }.longPress.contains("¥"))

    }
}
