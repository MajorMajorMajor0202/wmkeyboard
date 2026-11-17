package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Uses the production Rust lattice and real conversion/learning facades. */
class CompactPinyinComposerTest {
    private val composer = CompactPinyinComposer

    @Before fun prepare() {
        CjkLearning.store = null
        CjkLearning.enabled = true
        CjkConfig.jianpin = true
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = setOf("ni", "hao", "zhong", "guo", "xi", "an", "xian", "nin", "ne", "e", "shi", "jie")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        Jianpin.index = Jianpin.build(PinyinSyllables.valid)
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "ni\t你\t1000", "ni\t尼\t10", "hao\t好\t1000", "nihao\t你好\t2000",
            "zhong\t中\t1000", "guo\t国\t1000", "zhongguo\t中国\t2000",
            "xian\t先\t1000", "xian\t西安\t1000", "xi\t西\t1000", "an\t安\t1000",
            "nine\t你呢\t1000", "nin\t您\t1000", "e\t饿\t1000", "nine\t您饿\t1000", "ne\t呢\t1000",
            "shi\t世\t1000", "jie\t界\t1000", "shijie\t世界\t1000", "nihaoshijie\t你好世界\t3000",
        ))
    }

    @After fun reset() {
        CjkLearning.store = null
        CjkLearning.enabled = true
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = emptySet()
        T9Pinyin.index = emptyMap()
        Jianpin.index = Jianpin.EMPTY
        CjkConfig.jianpin = true
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
    }

    private fun taps(reading: String): String = CompactPinyin.encode(reading)

    @Test fun `reading picks constrain successive syllables and backspace undoes one pick`() {
        val original = taps("nihao")
        assertTrue("ni" in composer.readings(original))
        val first = composer.pickReading(original, "ni")!!
        assertTrue("hao" in composer.readings(first))
        assertEquals("你好", composer.candidates(first).first())
        val second = composer.pickReading(first, "hao")!!
        assertTrue(composer.readings(second).isEmpty())
        assertEquals("你好", composer.candidates(second).first())
        assertEquals("nihao", composer.composeBuffer(second))
        assertEquals(first, composer.backspaceBuffer(second))
        assertNull(composer.pickReading(original, "invalid"))
    }

    @Test fun `double pinyin reading picks retain original two taps`() {
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        val original = taps("nihc")
        val first = composer.pickReading(original, "ni")!!
        assertTrue("hao" in composer.readings(first))
        val selected = composer.pickReading(first, "hao")!!
        assertEquals("你好", composer.candidates(selected).first())
        assertEquals(first, composer.backspaceBuffer(selected))
        val single = composer.pickReading(taps("ni"), "ni")!!
        assertEquals(taps("ni"), composer.backspaceBuffer(single))
    }

    @Test fun `candidate preview follows each ranked reading`() {
        val buffer = taps("ni")
        val candidates = composer.candidates(buffer, 128)
        assertEquals("ni", composer.candidatePreview(buffer, candidates.indexOf("你")))
        assertEquals("nin", composer.candidatePreview(buffer, candidates.indexOf("您")))
        assertEquals(composer.candidatePreview(buffer, 0), composer.composeBuffer(buffer))
    }

    @Test fun `Xiaohe pairs use two keys and never jianpin words`() {
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        PinyinSyllables.valid = setOf("mian", "ma", "ni", "hao")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "mian\t面\t1000", "mama\t妈妈\t10000", "nihao\t你好\t1000",
        ))
        assertTrue(composer.candidates(taps("m")).isEmpty())
        assertEquals("面", composer.candidates(taps("mm")).first())
        assertFalse("妈妈" in composer.candidates(taps("mm")))
        assertEquals("你好", composer.candidates(taps("nihc")).first())
        assertEquals(2, composer.consumedFor(taps("mm"), "面"))
        assertTrue(composer.candidates(composer.backspaceBuffer(taps("mm"))).isEmpty())
        assertEquals("面", composer.candidates(taps("mm'")).first())
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        assertTrue("妈妈" in composer.candidates(taps("mm")))
    }

    @Test fun `Chinese single characters and phrases use the common dictionary`() {
        assertTrue("你" in composer.candidates(taps("ni")))
        assertEquals("你好", composer.candidates(taps("nihao")).first())
        assertEquals("中国", composer.candidates(taps("zhongguo")).first())
        assertEquals("你好世界", composer.candidates(taps("nihaoshijie")).first())
        assertTrue("你好" in composer.candidates(taps("niha")))
        assertNull(composer.missingPack)
    }

    @Test fun `jianpin accepts ambiguous initial keys and mixed full syllables`() {
        assertTrue("你好" in composer.candidates(taps("nh")))
        assertTrue("中国" in composer.candidates(taps("zg")))
        assertTrue("中国" in composer.candidates(taps("zhg")))
        assertTrue("你好" in composer.candidates(taps("nih")))
        CjkConfig.jianpin = false
        assertFalse("你好" in composer.candidates(taps("nh")))
    }

    @Test fun `both cross syllable segmentations reach the real decoder`() {
        val xian = composer.candidates(taps("xian"), 128)
        assertTrue("先" in xian)
        assertTrue("西安" in xian)
        val nine = composer.candidates(taps("nine"), 128)
        assertTrue("你呢" in nine)
        assertTrue("您饿" in nine)
        assertFalse("先" in composer.candidates(taps("xi'an"), 128))
    }

    @Test fun `prefix commit and backspace count keys rather than decoded letters`() {
        val buffer = taps("nihao")
        val choices = composer.candidates(buffer, 128)
        val index = choices.indexOf("你")
        assertTrue(index >= 0)
        assertEquals(2, composer.consumedForIndex(buffer, index))
        assertEquals(2, composer.consumedFor(buffer, "你"))
        assertTrue("好" in composer.candidates(buffer.substring(2)))
        assertEquals(taps("niha"), composer.backspaceBuffer(buffer))
        assertEquals("", composer.backspaceBuffer(taps("n")))
        assertEquals("", composer.backspaceBuffer(""))
    }

    @Test fun `preview and enter never expose internal key symbols`() {
        assertEquals("nihao", composer.composeBuffer(taps("nihao")))
        assertEquals("nihao", composer.typedReading(taps("nihao")))
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        assertTrue(composer.candidates(taps("ni")).isEmpty())
        assertEquals("bu", composer.composeBuffer(taps("ni")))
        assertNotNull(composer.missingPack)
    }

    @Test fun `explicit popup letters constrain only their own position`() {
        val buffer = composer.bufferKey("n", null) + composer.bufferKey("u", "ui")
        assertEquals("n", buffer.take(1))
        assertTrue("你" in composer.candidates(buffer))
        assertEquals("ni", composer.bufferKey("ni", "bn"))
    }

    @Test fun `user choices are shared with ordinary Pinyin and invalidate ranking`() {
        val history = CjkUserHistory(null)
        CjkLearning.store = history
        val buffer = taps("ni")
        val index = composer.candidates(buffer, 128).indexOf("尼")
        assertTrue(index >= 0)
        composer.learnChoice(buffer, index)
        assertEquals(1, history.countFor("pinyin", "ni", "尼"))
        assertEquals("尼", composer.candidates(buffer).first())
        assertEquals("尼", PinyinComposer.candidates("ni").first())
        CjkLearning.enabled = false
        composer.learnChoice(buffer, composer.candidates(buffer).indexOf("尼"))
        assertEquals(1, history.countFor("pinyin", "ni", "尼"))
    }

    @Test fun `candidate menu uses existing Pinyin history`() {
        CjkLearning.store = CjkUserHistory(null)
        val buffer = taps("ni")
        val index = composer.candidates(buffer).indexOf("尼")
        composer.adjustChoice(buffer, index, CandidateAdjustment.BLOCK)
        assertFalse("尼" in composer.candidates(buffer))
        assertFalse("尼" in PinyinComposer.candidates("ni"))
    }

    @Test fun `ordinary Pinyin T9 and English Compact retain their composer`() {
        val han = ScriptRegistry[ScriptId.HAN]
        assertSame(PinyinComposer, composerFor(han, ComposerType.PINYIN))
        assertSame(T9PinyinComposer, composerFor(han, ComposerType.T9_PINYIN))
        assertSame(NoComposer, BuiltInLayouts.COMPACT.resolvedComposer())
        assertTrue("你好" in PinyinComposer.candidates("nihao"))
        assertTrue("你好" in T9PinyinComposer.candidates(T9Pinyin.encode("nihao")))
        assertEquals("q", NoComposer.bufferKey("q", "qw"))
    }

    @Test fun `Chinese layout reuses all keys and is offered by the language picker`() {
        val layout = BuiltInLayouts.COMPACT_PINYIN
        assertSame(CompactPinyinComposer, layout.resolvedComposer())
        val originalRows = BuiltInLayouts.COMPACT.layers.getValue(LayoutLayer.LETTERS.key).rows
        val changedRows = layout.layers.getValue(LayoutLayer.LETTERS.key).rows
        assertEquals(originalRows.map { row -> row.map { it.copy(width = 1f, hint = null) } },
            changedRows.map { row -> row.map { it.copy(width = 1f, hint = null) } })
        assertEquals(listOf(10f, 9f, 10f, 10f), changedRows.map { row -> row.sumOf { it.width.toDouble() }.toFloat() })
        assertEquals("12", changedRows[0][0].hint)
        assertEquals("@#", changedRows[1][0].hint)
        assertEquals(1f, changedRows[1].last().width)
        assertEquals(1.5f, changedRows[2].first().width)
        assertEquals(BuiltInLayouts.COMPACT.tabletExpand, layout.tabletExpand)
        assertEquals("zh", layout.langId)
        assertTrue(layout.id in LanguageRegistry.byId("zh").layoutIds)
        val rows = layout.layers.getValue(LayoutLayer.LETTERS.key).rows
        assertEquals(listOf(5, 5, 4), rows.take(3).map { row -> row.count { it.letters != null || it.output in listOf("l", "m") } })
    }
}
