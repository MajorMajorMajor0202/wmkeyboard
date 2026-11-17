package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PinyinLearningRegressionTest {
    @Before fun prepare() {
        CjkLearning.store=CjkUserHistory(null)
        CjkLearning.enabled=true
        CjkContext.before=""
        CjkConfig.doublePinyin=DoublePinyinScheme.XIAOHE
        CjkConfig.fuzzyPinyin=true
        CjkConfig.fuzzyPinyinPairs=setOf("en-eng")
        CjkConfig.traditionalOutput=false
        PinyinSyllables.valid=setOf("meng","men","ma")
        CjkDictionaries.pinyinNgrams=CjkNgrams.EMPTY
        CjkDictionaries.pinyin=ConversionDictionary.parse(sequenceOf("meng\t猛\t1000","men\t门\t1000","ma\t马\t1000"))
    }

    @After fun reset() {
        CjkLearning.store=null
        CjkLearning.enabled=true
        CjkConfig.doublePinyin=DoublePinyinScheme.OFF
        CjkConfig.fuzzyPinyin=false
        CjkDictionaries.pinyin=ConversionDictionary.EMPTY
        PinyinSyllables.valid=emptySet()
    }

    private fun selectTwice(composer: Composer, buffer: String) {
        val first=composer.candidates(buffer,128).indexOf("猛")
        assertTrue("First character must be selectable",first>=0)
        val end=composer.consumedForIndex(buffer,first)
        composer.learnChoice(buffer,first)
        val rest=composer.remainderAfterCommit(buffer.substring(end))
        val second=composer.candidates(rest,128).indexOf("猛")
        assertTrue("Second character must be selectable",second>=0)
        composer.learnChoice(rest,second)
        assertEquals(1,CjkLearning.store!!.countFor("pinyin","mengmeng","猛猛"))
        assertTrue(composer.candidates(buffer,128).toString(),"猛猛" in composer.candidates(buffer,128))
    }

    @Test fun compactDoublePinyinLearnsTwoSelectedCharacters() {
        selectTwice(CompactPinyinComposer,CompactPinyin.encode("mfmf"))
    }

    @Test fun selectedGlideReadingsLearnTwoSelectedCharacters() {
        val part=CompactReadingBuffer.selected(CompactPinyin.encode("mf"),"meng")
        selectTwice(CompactPinyinComposer,part+part)
    }

    @Test fun ordinaryDoublePinyinLearnsTwoSelectedCharacters() {
        selectTwice(PinyinComposer,"mfmf")
    }

    @Test fun personalWordIsSearchableWithoutAutomaticLearningAndCanBeRemoved() {
        CjkLearning.enabled=false
        val history=CjkLearning.store!!
        val entries=PinyinPersonalWords.entries(CjkDictionaries.pinyin,listOf("猛猛"))
        assertTrue(entries["mengmeng"]!!.containsKey("猛猛"))
        history.setPersonalPinyin(entries)
        assertTrue("猛猛" in PinyinComposer.candidates("mfmf",128))
        assertTrue("猛猛" in CompactPinyinComposer.candidates(CompactPinyin.encode("mfmf"),128))
        history.setPersonalPinyin(emptyMap())
        assertEquals(0,history.countFor("pinyin","mengmeng","猛猛"))
    }

    private fun compoundDictionary() {
        PinyinSyllables.valid=setOf("qing","xiao","shuo","ji","fan","dan","qi","ren")
        CjkDictionaries.pinyin=ConversionDictionary.parse(sequenceOf(
            "qingxiaoshuo\t轻小说\t1000", "jifan\t机翻\t10", "jidan\t鸡蛋\t100",
            "jiqiren\t机器人\t1000"))
    }

    private fun pick(composer: Composer, buffer: String, word: String): String {
        val candidates=composer.candidates(buffer,128)
        val index=candidates.indexOf(word)
        assertTrue("$word missing in $candidates",index>=0)
        val consumed=composer.consumedForIndex(buffer,index)
        composer.learnChoice(buffer,index)
        CjkContext.committed(word)
        return composer.remainderAfterCommit(buffer.substring(consumed))
    }

    @Test fun compoundPrefixesAndEightSyllablePhraseAreLearnedAndRetrieved() {
        compoundDictionary()
        val buffer=CompactPinyin.encode("qkxnuojifjjiqirf")
        val rest=pick(CompactPinyinComposer,buffer,"轻小说")
        val tail=pick(CompactPinyinComposer,rest,"机翻")
        assertEquals(1,CjkLearning.store!!.countFor("pinyin","qingxiaoshuojifan","轻小说机翻"))
        pick(CompactPinyinComposer,tail,"机器人")
        assertEquals(1,CjkLearning.store!!.countFor("pinyin","qingxiaoshuojifanjiqiren","轻小说机翻机器人"))
        CjkContext.before=""
        assertEquals("轻小说机翻机器人",CompactPinyinComposer.candidates(buffer,128).first())
        assertEquals("轻小说机翻",CompactPinyinComposer.candidates(CompactPinyin.encode("qkxnuojifj"),128).first())
    }

    @Test fun extendingRemainingBufferKeepsTheLearningRun() {
        compoundDictionary()
        val rest=pick(CompactPinyinComposer,CompactPinyin.encode("qkxnuoji"),"轻小说")
        pick(CompactPinyinComposer,rest+CompactPinyin.encode("fj"),"机翻")
        assertEquals(1,CjkLearning.store!!.countFor("pinyin","qingxiaoshuojifan","轻小说机翻"))
    }

    @Test fun compactUsesPreviousSelectionToDisambiguateTheSameTail() {
        compoundDictionary()
        val tail=CompactPinyin.encode("jifj")
        assertEquals("鸡蛋",CompactPinyinComposer.candidates(tail,128).first())
        repeat(20) { CjkLearning.learnPair("pinyin","轻小说","机翻") }
        CjkContext.before="轻小说"
        assertEquals("机翻",CompactPinyinComposer.candidates(tail,128).first())
        // The buffer stays the same; a context-only change must invalidate ranking.
        CjkContext.before=""
        assertEquals("鸡蛋",CompactPinyinComposer.candidates(tail,128).first())
    }

    @Test fun consecutiveWordsAcrossSeparateBuffersLearnACompoundButPunctuationBreaksIt() {
        for(composer in listOf(CompactPinyinComposer,PinyinComposer)) {
            CjkLearning.store=CjkUserHistory(null)
            CjkContext.before=""
            compoundDictionary()
            fun keys(s: String)=if(composer===CompactPinyinComposer) CompactPinyin.encode(s) else s
            pick(composer,keys("qkxnuo"),"轻小说")
            pick(composer,keys("jifj"),"机翻")
            pick(composer,keys("jiqirf"),"机器人")
            assertEquals(1,CjkLearning.store!!.countFor("pinyin","qingxiaoshuojifanjiqiren","轻小说机翻机器人"))
            CjkContext.before=""
            assertEquals("轻小说机翻机器人",composer.candidates(keys("qkxnuojifjjiqirf"),128).first())
            pick(composer,keys("jifj"),"机翻")
            CjkContext.before=""
            pick(composer,keys("jiqirf"),"机器人")
            assertEquals(0,CjkLearning.store!!.countFor("pinyin","jifanjiqiren","机翻机器人"))
        }
    }
}
