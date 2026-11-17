package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DoublePinyinDeleteTest {
    @After fun reset() {
        CjkConfig.doublePinyinSyllableDelete = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
    }
    @Test fun `ordinary double pinyin deletes pairs and incomplete first keys`() {
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        CjkConfig.doublePinyinSyllableDelete = true
        assertEquals("ni", PinyinComposer.backspaceBuffer("nihc"))
        assertEquals("ni", PinyinComposer.backspaceBuffer("nih"))
        assertEquals("ni", PinyinComposer.backspaceBuffer("ni'hc'"))
        assertEquals("", PinyinComposer.backspaceBuffer("mm"))
        assertEquals("", PinyinComposer.backspaceBuffer("m"))
        assertEquals("", PinyinComposer.backspaceBuffer(""))
    }
    @Test fun `option off and full pinyin retain character deletion`() {
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        assertEquals("nih", PinyinComposer.backspaceBuffer("nihc"))
        CjkConfig.doublePinyinSyllableDelete = true
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        assertEquals("niha", PinyinComposer.backspaceBuffer("nihao"))
        assertEquals(CompactPinyin.encode("niha"), CompactPinyinComposer.backspaceBuffer(CompactPinyin.encode("nihao")))
    }
    @Test fun `compact raw and picked readings delete one complete syllable`() {
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        CjkConfig.doublePinyinSyllableDelete = true
        val ni = CompactPinyin.encode("ni")
        val hc = CompactPinyin.encode("hc")
        assertEquals(ni, CompactPinyinComposer.backspaceBuffer(ni + hc))
        assertEquals(ni, CompactPinyinComposer.backspaceBuffer(ni + hc.take(1)))
        val picked = CompactReadingBuffer.selected(ni, "ni")
        assertEquals("", CompactPinyinComposer.backspaceBuffer(picked))
        assertEquals(picked, CompactPinyinComposer.backspaceBuffer(picked + hc))
        assertEquals(picked, CompactPinyinComposer.backspaceBuffer(picked + CompactReadingBuffer.selected(hc, "hao") + "'"))
        CjkConfig.doublePinyinSyllableDelete = false
        assertEquals(ni, CompactPinyinComposer.backspaceBuffer(picked))
    }
    @Test fun `probability ranking prefers frequent readings over first group letters`() {
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        PinyinSyllables.valid = setOf("bu", "ni")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("bu\t不\t1", "ni\t你\t100000"))
        val buffer = CompactPinyin.encode("ni")
        assertEquals("bu", CompactPinyin.literal(buffer))
        assertEquals("你", CompactPinyinComposer.candidates(buffer).first())
        assertEquals("ni", CompactPinyinComposer.candidatePreview(buffer, 0))
        assertEquals("中文 十四键拼音 (Chinese 14-Key Pinyin)", com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.COMPACT_PINYIN.name)
    }
}
