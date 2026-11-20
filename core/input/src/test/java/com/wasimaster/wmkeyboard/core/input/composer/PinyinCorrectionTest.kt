package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import org.junit.Assert.*
import org.junit.Test

class PinyinCorrectionTest {
    @Test fun sharedKeysAreAlternateReadingsAndDoNotBecomeRepairs() {
        val slots = listOf(PinyinCorrection.Slot(0, 2, "wu", listOf(
            PinyinCorrection.Option("qi", "qi", 0.0),
            PinyinCorrection.Option("gu", "gu", 2.0),
        ), locked = false, full = false))
        val dictionary = ConversionDictionary.parse(sequenceOf("qi\t其\t1000", "gu\t骨\t300"))
        assertEquals(listOf("骨"), PinyinCorrection.words("wu", slots, dictionary, doublePinyin = true).map { it.text })
    }
    private val scheme = DoublePinyin.Table(emptyMap(), emptyMap(),
        codes = mapOf("ni" to "ni", "mi" to "mi", "hc" to "hao", "zz" to "zuo", "nm" to "mi"))
    private val valid = scheme.codes.values.toSet()

    private fun withInventory(block: () -> Unit) {
        val previous = PinyinSyllables.valid
        try {
            PinyinSyllables.valid = valid
            block()
        } finally {
            PinyinSyllables.valid = previous
        }
    }

    @Test fun thirteenKeyFullPinyinRepairsAnAdjacentKeyAndPreservesTheNextSyllable() = withInventory {
        val oldDictionary = CjkDictionaries.pinyin
        val oldScheme = CjkConfig.doublePinyin
        try {
            CjkConfig.doublePinyin = DoublePinyinScheme.OFF
            val dictionary = ConversionDictionary.parse(sequenceOf("mihao\t米号\t100", "nihao\t你好\t1000"))
            CjkDictionaries.pinyin = dictionary
            val layout = BuiltInLayouts.PINYIN_13
            val groups = layout.layers.getValue(LayoutLayer.LETTERS.key).rows.flatten().mapNotNull { it.letters }
            val buffer = CompactPinyin.encode("mihao", groups)
            val composer = layout.resolvedComposer()
            assertSame(CompactPinyinComposer, composer)
            assertEquals("lm", CompactPinyin.letters(buffer.first()))
            assertEquals("米号", composer.candidates(buffer).first())

            val slots = PinyinCorrection.slots(buffer, table = null, compact = true, allOptions = false) { index, group, letter ->
                when {
                    letter in group -> 0.0
                    index == 0 && letter == 'n' -> 2.0
                    else -> 12.0
                }
            }
            val repair = PinyinCorrection.words(buffer, slots, dictionary).single()
            assertEquals("你好", repair.text)
            val corrected = repair.replaceIn(buffer, slots)
            assertTrue(corrected.endsWith(buffer.drop(2)))
            assertEquals("你好", composer.candidates(corrected).first())
        } finally {
            CjkDictionaries.pinyin = oldDictionary
            CjkConfig.doublePinyin = oldScheme
        }
    }

    @Test fun manualPickerIncludesDistantSyllablesAndPreservesUnfinishedTail() = withInventory {
        val buffer = "ni'hcn"
        val slots = PinyinCorrection.slots(buffer, scheme, false) { _, group, letter ->
            if (letter in group) 0.0 else 15.0
        }
        assertEquals(valid, slots[1].options.map { it.reading }.toSet())
        val choice = slots[1].options.single { it.reading == "zuo" }
        assertEquals("ni'zzn", slots[1].replaceIn(buffer, choice))
        val unfinished = slots.last()
        assertEquals(valid, unfinished.options.map { it.reading }.toSet())
        val completion = unfinished.options.single { it.reading == "ni" }
        assertEquals("ni'hcni", unfinished.replaceIn(buffer, completion))
        assertFalse(PinyinCorrection.coversBuffer(buffer, slots))
    }

    @Test fun touchEvidenceChoosesTheClosestAliasAndOffersItsWholeWord() = withInventory {
        val slots = PinyinCorrection.slots("nihc", scheme, false) { index, group, letter ->
            when {
                letter in group -> 0.0
                index == 1 && letter == 'm' -> 1.0
                else -> 12.0
            }
        }
        val choice = slots.first().options.single { it.reading == "mi" }
        assertEquals("nm", choice.replacement)
        assertEquals(1.0, choice.cost, 0.001)
        val dict = ConversionDictionary.parse(sequenceOf("mihao\t\u7c73\u53f7\t120"))
        assertEquals("\u7c73\u53f7", PinyinCorrection.words("nihc", slots, dict).first().text)
    }

    @Test fun stricterConfidenceFiltersWordRepairsWhileKeepingManualOptions() = withInventory {
        val buffer = "nihc"
        val dict = ConversionDictionary.parse(sequenceOf("mihao\t\u7c73\u53f7\t120"))
        val slots = PinyinCorrection.slots(buffer, scheme, false) { index, group, letter ->
            when {
                letter in group -> 0.0
                index == 1 && letter == 'm' -> 5.0
                else -> 12.0
            }
        }
        val default = PinyinCorrection.Tuning()
        val adaptive = PinyinCorrection.Tuning(adaptiveMultiplier = 1.5)
        assertEquals(1, PinyinCorrection.words(buffer, slots, dict, maxCost = default.wordCostLimit).size)
        assertTrue(PinyinCorrection.words(buffer, slots, dict, maxCost = adaptive.wordCostLimit).isEmpty())
        assertTrue(slots.first().options.any { it.reading == "mi" })
    }

    @Test fun readingChoicesPromoteLikelyWordsAndExcludeDistantKeys() {
        val close = PinyinCorrection.Option("ni", "ni", 2.0)
        val likely = PinyinCorrection.Option("mi", "mi", 3.0)
        val distant = PinyinCorrection.Option("zuo", "zz", 9.0)
        val slots = listOf(
            PinyinCorrection.Slot(0, 2, "bi", listOf(close, likely, distant), locked = false, full = false),
        )
        val words = listOf(PinyinCorrection.Word("\u7c73", 0, likely, 7.0))
        val choices = PinyinCorrection.readingChoices(slots, ConversionDictionary.EMPTY, words, caret = -1)
        assertEquals(listOf("mi", "ni"), choices.map { it.option.reading })
    }

    @Test fun lockedCompactSyllableCanBeChangedWithoutRewritingItsNeighbour() = withInventory {
        val first = CompactReadingBuffer.selected("ni", "ni")
        val buffer = first + CompactReadingBuffer.selected("hc", "hao")
        val slot = PinyinCorrection.slots(buffer, scheme, true) { _, _, _ -> 0.0 }.last()
        val chosen = slot.options.single { it.reading == "zuo" }
        assertEquals(first + CompactReadingBuffer.selected("zz", "zuo"),
            slot.replaceIn(buffer, chosen))
    }

    @Test fun doublePinyinCombinesASoundConfusionAndTransposedKeys() {
        val oldInventory = PinyinSyllables.valid
        val oldPairs = CjkConfig.fuzzyPinyinPairs
        val oldFuzzy = CjkConfig.fuzzyPinyin
        try {
            PinyinSyllables.valid = setOf("li", "ni", "cang", "hao")
            CjkConfig.fuzzyPinyinPairs = PinyinFuzzy.ALL_PAIRS
            CjkConfig.fuzzyPinyin = false
            val table = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
            val buffer = "li'ch"
            val slots = PinyinCorrection.slots(buffer, table, false) { _, group, letter ->
                if (letter in group) 0.0 else 12.0
            }
            val dict = ConversionDictionary.parse(sequenceOf("nihao\t你好\t1500", "licang\t李沧\t30"))
            val repair = PinyinCorrection.words(buffer, slots, dict, doublePinyin = true).single()
            assertEquals("你好", repair.text)
            assertEquals("ni'hc", repair.replaceIn(buffer, slots))
            assertTrue(PinyinCorrection.words(buffer, slots, dict).isEmpty())
            assertTrue(PinyinCorrection.words(buffer, slots, dict, maxCost = 3.0, doublePinyin = true).isEmpty())
            assertTrue(PinyinCorrection.words(buffer, listOf(slots[0].copy(locked = true), slots[1]), dict,
                doublePinyin = true).isEmpty())
        } finally {
            PinyinSyllables.valid = oldInventory
            CjkConfig.fuzzyPinyinPairs = oldPairs
            CjkConfig.fuzzyPinyin = oldFuzzy
        }
    }

    @Test fun compactSelectionsKeepAPhraseConvertibleAcrossSeparators() = withInventory {
        val oldDictionary = CjkDictionaries.pinyin
        try {
            CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("nihao\t你好\t1500", "ni\t你\t100"))
            val first = CompactReadingBuffer.selected("ni", "ni")
            val buffer = first + "'" + CompactReadingBuffer.selected("hc", "hao") + "'"
            val composer = CompactPinyinComposer
            assertEquals("你好", composer.candidates(buffer).first())
            assertEquals(buffer.length, composer.consumedForIndex(buffer, 0))
            assertEquals(first + "'hc'", composer.undoReading(buffer))
        } finally {
            CjkDictionaries.pinyin = oldDictionary
        }
    }
}
