package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinGlideTest {
    private val inventory = setOf("ni", "hao", "zhong", "guo")
    private fun dictionary() = ConversionDictionary.parse(sequenceOf(
        "ni\t你\t100", "hao\t好\t80", "nihao\t你好\t500", "nihao\t拟好\t20", "zhongguo\t中国\t400",
    ))

    @After fun reset() {
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
    }

    @Test fun `full and Compact glides retain readings and optional initials`() {
        val words = PinyinGlide.spellings(dictionary(), inventory).toMap()
        assertEquals(500, words["nihao"])
        assertEquals(500, words["nh"])
        assertFalse("nh" in PinyinGlide.spellings(dictionary(), inventory, jianpin = false).toMap())
        assertTrue(PinyinComposer.glidesPinyinReadings)
        assertTrue(CompactPinyinComposer.glidesPinyinReadings)
        assertFalse(T9PinyinComposer.glidesPinyinReadings)
    }

    @Test fun `every built in double Pinyin scheme glides on its own key codes`() {
        for (scheme in DoublePinyinScheme.entries.filter { it != DoublePinyinScheme.OFF && it != DoublePinyinScheme.CUSTOM }) {
            val table = DoublePinyin.tableFor(scheme)!!
            val keys = DoublePinyin.keysOf(table, inventory)
            val words = PinyinGlide.spellings(dictionary(), inventory, table).toMap()
            val code = keys.getValue("ni") + keys.getValue("hao")
            assertEquals(scheme.name, 500, words[code])
            assertFalse("nh" in words)
            CjkConfig.doublePinyin = scheme
            PinyinSyllables.valid = inventory
            CjkDictionaries.pinyin = dictionary()
            assertTrue("$scheme $code", "你好" in PinyinComposer.candidates(code))
        }
    }

    @Test fun `custom double Pinyin codes are used without expanding letter combinations`() {
        val custom = DoublePinyin.Table(emptyMap(), emptyMap(), codes = mapOf("ab" to "ni", "cd" to "hao"))
        val words = PinyinGlide.spellings(dictionary(), inventory, custom).toMap()
        assertEquals(500, words["abcd"])
        assertFalse("nihao" in words)
        assertTrue(PinyinGlide.spellings(ConversionDictionary.EMPTY, inventory, custom).isEmpty())
    }

    @Test fun `separator joins an active reading in app and keyboard owned fields`() {
        for (composer in listOf(PinyinComposer, CompactPinyinComposer)) {
            assertTrue(composer.buffersChar('\'', "ni"))
            assertFalse(composer.buffersChar('\'', ""))
        }
    }
}
